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
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateItem
import io.nook.core.catalog.CatalogBehavior.Companion.SOMEBODY
import io.nook.core.catalog.CoreCatalog
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
 * Closes the read surface: four operations, no way to reach a deleted row, no
 * way to ask about readiness, and no effect on the store.
 *
 * Two of those are assertions about absence, which are worth stating out loud
 * precisely because absence is easy to erode. An "include deleted" flag would
 * look like a small kindness on any one operation and would quietly undo the
 * rule that deletion is final. A readiness operation would look like a
 * shorthand for three filter parts and would take back the thing composing them
 * bought: the same question asked inside one epic.
 */
class ReadServiceSurfaceTest {

    private val db = Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase())
    private val writes = CoreCatalog(db).forActor(SOMEBODY)
    private val reads = ReadService(db)

    private fun publicMethodsOf(type: Class<*>) =
        type.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic }

    @Test
    fun `the public surface is exactly the four reads`() {
        assertEquals(
            setOf("getProject", "listProjects", "getItem", "listItems"),
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
    fun `readiness is nowhere in the surface, the entities, or the vocabulary`() {
        // The three shapes it could take, each ruled out where it would appear.
        val readinessOperations = publicMethodsOf(ReadService::class.java)
            .map { it.name }
            .filter { it.contains("ready", ignoreCase = true) }
        assertEquals(emptyList(), readinessOperations)

        val readinessFields = listOf(ItemFilter::class.java, ProjectItem::class.java)
            .flatMap { type -> type.declaredFields.map { "${type.simpleName}.${it.name}" } }
            .filter { it.contains("ready", ignoreCase = true) }
        assertEquals(emptyList(), readinessFields)

        val project = writes.createProject(CreateProject("No Such Status"))
        val rejected = assertFailsWith<StructuredErrorException> {
            reads.listItems(project.slug, ItemFilter(statuses = listOf("ready")))
        }
        assertEquals(ErrorCode.VALIDATION_FAILED, rejected.error.code)
    }

    @Test
    fun `every one of the four reads leaves every stored row and timestamp untouched`() {
        val project = writes.createProject(CreateProject("Reads Change Nothing"))
        writes.createRelease(project.slug, CreateRelease("v1"))
        writes.createItem(project.slug, CreateItem(type = "epic", name = "An epic", releaseRef = "v1"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "A blocker", parentRef = "an-epic"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "A leaf", parentRef = "an-epic"))
        writes.updateItem(
            project.slug, "a-leaf",
            UpdateItem(blockedBy = FieldChange.Set(listOf("a-blocker"))),
        )
        writes.createItem(project.slug, CreateItem(type = "bug", name = "Doomed"))
        writes.deleteItem(project.slug, "doomed")

        val before = snapshot()

        reads.getProject(project.slug)
        reads.listProjects()
        reads.getItem(project.slug, "a-leaf")
        reads.listItems(project.slug, ItemFilter(types = listOf("task"), statuses = listOf("todo")))
        reads.listItems(project.slug, ItemFilter(heldUp = false))

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
            { reads.listItems("no-such-project", ItemFilter(heldUp = false)) },
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
