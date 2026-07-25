package io.nook.core.read

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.CreateRelease
import io.nook.contract.ErrorCode
import io.nook.contract.FieldChange
import io.nook.contract.ItemFilter
import io.nook.contract.ParentFilter
import io.nook.contract.Project
import io.nook.contract.ProjectItem
import io.nook.contract.Release
import io.nook.contract.SetItemBlockedBy
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateItem
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.allStructureTables
import io.nook.core.write.WriteService
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Closes the read surface: five operations, no way to reach a deleted row, and
 * no effect on the store.
 *
 * The second of those is an assertion about absence, which is worth stating out
 * loud precisely because absence is easy to erode: an "include deleted" flag
 * would look like a small kindness on any one operation and would quietly undo
 * the rule that deletion is final. There is no such argument, and nothing a
 * caller can hold reports itself deleted either.
 */
class ReadServiceSurfaceTest {

    private val db = Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase())
    private val writes = WriteService(db)
    private val reads = ReadService(db)

    private fun publicMethodsOf(type: Class<*>) =
        type.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic }

    @Test
    fun `the public surface is exactly the five reads`() {
        assertEquals(
            setOf("getProject", "listProjects", "getItem", "listItems", "getReadyItems"),
            publicMethodsOf(ReadService::class.java).map { it.name }.toSet(),
        )
    }

    @Test
    fun `no read takes anything but a reference and a filter`() {
        // Nothing else can be passed in, so there is no argument to smuggle a
        // request for deleted rows through — not a flag, not a mode, not a scope.
        val parameterTypes = publicMethodsOf(ReadService::class.java)
            .flatMap { it.parameterTypes.asIterable() }
            .toSet()

        assertEquals(setOf(String::class.java, ItemFilter::class.java), parameterTypes)
    }

    @Test
    fun `neither the filter nor any returned entity mentions deletion`() {
        val fieldNames = listOf(
            ItemFilter::class.java,
            ParentFilter.Epic::class.java,
            Project::class.java,
            ProjectItem::class.java,
            Release::class.java,
        ).flatMap { type -> type.declaredFields.map { "${type.simpleName}.${it.name}" } }

        val mentioningDeletion = fieldNames.filter { it.contains("delet", ignoreCase = true) }
        assertEquals(emptyList(), mentioningDeletion)
    }

    @Test
    fun `the readiness question takes no filter at all`() {
        val readyItems = publicMethodsOf(ReadService::class.java).single { it.name == "getReadyItems" }

        assertEquals(listOf(String::class.java), readyItems.parameterTypes.toList())
    }

    @Test
    fun `every one of the five reads leaves every stored row and timestamp untouched`() {
        val project = writes.createProject(CreateProject("Reads Change Nothing"))
        writes.createRelease(project.slug, CreateRelease("v1"))
        writes.createItem(project.slug, CreateItem(type = "epic", name = "An epic", releaseRef = "v1"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "A blocker", parentRef = "an-epic"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "A leaf", parentRef = "an-epic"))
        writes.setItemBlockedBy(project.slug, "a-leaf", SetItemBlockedBy(listOf("a-blocker")))
        writes.createItem(project.slug, CreateItem(type = "bug", name = "Doomed"))
        writes.deleteItem(project.slug, "doomed")

        val before = snapshot()

        reads.getProject(project.slug)
        reads.listProjects()
        reads.getItem(project.slug, "a-leaf")
        reads.listItems(project.slug, ItemFilter(types = listOf("task"), statuses = listOf("todo")))
        reads.getReadyItems(project.slug)

        assertEquals(before, snapshot())
    }

    @Test
    fun `a failing read is always validation_failed or not_found, never a write's verdict`() {
        val project = writes.createProject(CreateProject("Only Two Codes"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "A leaf"))
        val gone = writes.createItem(project.slug, CreateItem(type = "task", name = "Gone"))
        writes.deleteItem(project.slug, "gone")

        val attempts = listOf<() -> Unit>(
            { reads.getProject("no-such-project") },
            { reads.getProject(Uuid.random().toString()) },
            { reads.getItem(project.slug, "no-such-item") },
            { reads.getItem(project.slug, gone.id.toString()) },
            { reads.getItem("no-such-project", "a-leaf") },
            { reads.listItems("no-such-project", ItemFilter()) },
            { reads.listItems(project.slug, ItemFilter(types = listOf("story"))) },
            { reads.listItems(project.slug, ItemFilter(statuses = listOf("blocked"))) },
            { reads.listItems(project.slug, ItemFilter(types = emptyList())) },
            { reads.listItems(project.slug, ItemFilter(parents = listOf(ParentFilter.Epic("a-leaf")))) },
            { reads.listItems(project.slug, ItemFilter(parents = listOf(ParentFilter.Epic("gone")))) },
            { reads.listItems(project.slug, ItemFilter(releases = listOf("no-such-release"))) },
            { reads.getReadyItems("no-such-project") },
        )

        attempts.forEach { attempt ->
            val failure = assertFailsWith<StructuredErrorException> { attempt() }
            assertTrue(
                failure.error.code in setOf(ErrorCode.VALIDATION_FAILED, ErrorCode.NOT_FOUND),
                "a read produced ${failure.error.code}: ${failure.error.message}",
            )
        }
    }

    @Test
    fun `the two codes a read never produces are reachable from a write`() {
        // The control behind the test above: conflict is not absent from reads
        // because nothing can raise it, but because reads cannot.
        val project = writes.createProject(CreateProject("Codes Belong To Writes"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Taken"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Other"))

        val conflict = assertFailsWith<StructuredErrorException> {
            writes.updateItem(project.slug, "other", UpdateItem(slug = FieldChange.Set("taken")))
        }

        assertEquals(ErrorCode.CONFLICT, conflict.error.code)
    }

    /** Every stored row of every structure table, rendered so any change shows. */
    private fun snapshot(): List<String> = transaction(db) {
        allStructureTables.flatMap { table ->
            table.selectAll().map { row ->
                table.tableName + table.columns.joinToString(prefix = "{", postfix = "}") {
                    "${it.name}=${row[it]}"
                }
            }
        }.sorted()
    }
}
