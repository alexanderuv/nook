package io.nook.core.read

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.CreateRelease
import io.nook.contract.ErrorCode
import io.nook.contract.FieldChange
import io.nook.contract.ItemFilter
import io.nook.contract.ParentFilter
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateItem
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.ProjectItemTable
import io.nook.core.write.WriteService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Listing a project's items: the filter in every shape the contract allows, the
 * ordering, and what the listing does about rows that have been deleted.
 *
 * Each test works in its own project, so one test's items never widen another's
 * answer.
 */
class ReadServiceListingTest {

    private companion object {
        val db by lazy { Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase()) }
        val writes by lazy { WriteService(db) }
        val reads by lazy { ReadService(db) }
    }

    private fun assertFailsWithCode(code: ErrorCode, block: () -> Unit) {
        val failure = assertFailsWith<StructuredErrorException> { block() }
        assertEquals(code, failure.error.code)
    }

    /** The slugs a listing returned, in the order it returned them. */
    private fun slugsOf(projectSlug: String, filter: ItemFilter = ItemFilter()): List<String> =
        reads.listItems(projectSlug, filter).map { it.slug }

    @Test
    fun `a project holding no items lists an empty array rather than failing`() {
        val project = writes.createProject(CreateProject("Nothing Here"))

        assertEquals(emptyList(), reads.listItems(project.slug, ItemFilter()))
    }

    @Test
    fun `no filter returns every live item in the project`() {
        val project = writes.createProject(CreateProject("Unfiltered"))
        writes.createItem(project.slug, CreateItem(type = "epic", name = "An epic"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "A task"))
        writes.createItem(project.slug, CreateItem(type = "bug", name = "A bug"))
        writes.createItem(project.slug, CreateItem(type = "chore", name = "A chore"))
        writes.updateItem(project.slug, "a-task", UpdateItem(status = FieldChange.Set("in_progress")))

        assertEquals(setOf("an-epic", "a-task", "a-bug", "a-chore"), slugsOf(project.slug).toSet())
    }

    @Test
    fun `several values inside a part widen it`() {
        val project = writes.createProject(CreateProject("Widening"))
        listOf("todo" to "Waiting", "in_progress" to "Started", "done" to "Finished", "cancelled" to "Dropped")
            .forEach { (status, name) ->
                writes.createItem(project.slug, CreateItem(type = "task", name = name))
                if (status != "todo") {
                    writes.updateItem(
                        project.slug,
                        name.lowercase(),
                        UpdateItem(status = FieldChange.Set(status)),
                    )
                }
            }

        val open = slugsOf(project.slug, ItemFilter(statuses = listOf("todo", "in_progress")))

        assertEquals(setOf("waiting", "started"), open.toSet())
    }

    @Test
    fun `several parts narrow each other`() {
        val project = writes.createProject(CreateProject("Narrowing"))
        writes.createItem(project.slug, CreateItem(type = "bug", name = "Open bug"))
        writes.createItem(project.slug, CreateItem(type = "bug", name = "Closed bug"))
        writes.updateItem(project.slug, "closed-bug", UpdateItem(status = FieldChange.Set("done")))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Open task"))

        val openBugs = slugsOf(project.slug, ItemFilter(types = listOf("bug"), statuses = listOf("todo")))

        assertEquals(listOf("open-bug"), openBugs)
    }

    @Test
    fun `the same value supplied twice changes nothing`() {
        val project = writes.createProject(CreateProject("Repeated Value"))
        (1..3).forEach { writes.createItem(project.slug, CreateItem(type = "bug", name = "Bug $it")) }

        val listed = slugsOf(project.slug, ItemFilter(types = listOf("bug", "bug")))

        assertEquals(listOf("bug-3", "bug-2", "bug-1"), listed)
    }

    @Test
    fun `a value outside its vocabulary is refused, even in a project holding nothing`() {
        val empty = writes.createProject(CreateProject("Vocabulary, Empty"))

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            reads.listItems(empty.slug, ItemFilter(types = listOf("story")))
        }
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            reads.listItems(empty.slug, ItemFilter(statuses = listOf("blocked")))
        }
    }

    @Test
    fun `a part supplied with no values at all is refused rather than answered emptily`() {
        val project = writes.createProject(CreateProject("Empty Part"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Present"))

        listOf(
            ItemFilter(statuses = emptyList()),
            ItemFilter(types = emptyList()),
            ItemFilter(parents = emptyList()),
            ItemFilter(releases = emptyList()),
        ).forEach { filter ->
            assertFailsWithCode(ErrorCode.VALIDATION_FAILED) { reads.listItems(project.slug, filter) }
        }
    }

    @Test
    fun `the parent part matches an epic, or the absence of one`() {
        val project = writes.createProject(CreateProject("Parents"))
        writes.createItem(project.slug, CreateItem(type = "epic", name = "First epic"))
        writes.createItem(project.slug, CreateItem(type = "epic", name = "Second epic"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Under first", parentRef = "first-epic"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Under second", parentRef = "second-epic"))
        (1..3).forEach { writes.createItem(project.slug, CreateItem(type = "bug", name = "Loose bug $it")) }

        assertEquals(
            listOf("under-first"),
            slugsOf(project.slug, ItemFilter(parents = listOf(ParentFilter.Epic("first-epic")))),
        )
        assertEquals(
            setOf("loose-bug-1", "loose-bug-2", "loose-bug-3", "first-epic", "second-epic"),
            slugsOf(project.slug, ItemFilter(parents = listOf(ParentFilter.NoEpic))).toSet(),
        )
        assertEquals(
            setOf("first-epic", "second-epic"),
            slugsOf(
                project.slug,
                ItemFilter(types = listOf("epic"), parents = listOf(ParentFilter.NoEpic)),
            ).toSet(),
        )
        assertEquals(
            setOf("loose-bug-1", "loose-bug-2", "loose-bug-3"),
            slugsOf(
                project.slug,
                ItemFilter(types = listOf("bug"), parents = listOf(ParentFilter.NoEpic)),
            ).toSet(),
        )
    }

    @Test
    fun `a parent value naming something that is not an epic is a caller mistake`() {
        val project = writes.createProject(CreateProject("Leaf As Parent"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Add search"))

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            reads.listItems(project.slug, ItemFilter(parents = listOf(ParentFilter.Epic("add-search"))))
        }
    }

    @Test
    fun `the release part matches an item's own assignment, so no leaf ever matches`() {
        val project = writes.createProject(CreateProject("Releases"))
        writes.createRelease(project.slug, CreateRelease("v1"))
        writes.createRelease(project.slug, CreateRelease("v2"))
        listOf("First epic" to "v1", "Second epic" to "v1", "Third epic" to "v2").forEach { (name, release) ->
            writes.createItem(project.slug, CreateItem(type = "epic", name = name, releaseRef = release))
        }
        listOf("first-epic", "second-epic", "third-epic").forEachIndexed { index, epic ->
            writes.createItem(project.slug, CreateItem(type = "task", name = "Leaf $index", parentRef = epic))
        }

        assertEquals(
            setOf("first-epic", "second-epic"),
            slugsOf(project.slug, ItemFilter(releases = listOf("v1"))).toSet(),
        )
        assertEquals(
            emptyList(),
            slugsOf(project.slug, ItemFilter(releases = listOf("v1"), types = listOf("task"))),
        )
    }

    @Test
    fun `a listing considers live rows only, and a project emptied by deletion looks empty`() {
        val project = writes.createProject(CreateProject("Live Rows Only"))
        (1..3).forEach { writes.createItem(project.slug, CreateItem(type = "task", name = "Live $it")) }
        (1..2).forEach { writes.createItem(project.slug, CreateItem(type = "task", name = "Gone $it")) }
        writes.deleteItem(project.slug, "gone-1")
        writes.deleteItem(project.slug, "gone-2")

        assertEquals(setOf("live-1", "live-2", "live-3"), slugsOf(project.slug).toSet())

        listOf("live-1", "live-2", "live-3").forEach { writes.deleteItem(project.slug, it) }

        assertEquals(emptyList(), slugsOf(project.slug))
    }

    @Test
    fun `a deleted branch leaves the listing entirely, and filtering by it is not found`() {
        val project = writes.createProject(CreateProject("Deleted Branch"))
        val epic = writes.createItem(project.slug, CreateItem(type = "epic", name = "Doomed"))
        (1..4).forEach {
            writes.createItem(project.slug, CreateItem(type = "task", name = "Child $it", parentRef = "doomed"))
        }
        writes.createItem(project.slug, CreateItem(type = "task", name = "Survivor"))

        writes.deleteItem(project.slug, "doomed")

        assertEquals(listOf("survivor"), slugsOf(project.slug))
        val failure = assertFailsWith<StructuredErrorException> {
            reads.listItems(project.slug, ItemFilter(parents = listOf(ParentFilter.Epic(epic.id.toString()))))
        }
        assertEquals(ErrorCode.NOT_FOUND, failure.error.code)
    }

    @Test
    fun `an identical call returns an identical order, same-instant rows included`() {
        val project = writes.createProject(CreateProject("Stable Order"))
        val inSequence = (1..5).map {
            writes.createItem(project.slug, CreateItem(type = "task", name = "Sequential $it"))
                .let { item -> item.id to item.createdAt }
        }
        // Two rows sharing one creation instant, which no pair of separate write
        // transactions would produce: without the id breaking their tie, the
        // order between them is the query plan's to choose.
        val sharedInstant = Instant.now().plusSeconds(60)
        val twins = (1..2).map { number ->
            val id = Uuid.random()
            transaction(db) {
                ProjectItemTable.insert {
                    it[ProjectItemTable.id] = id
                    it[ProjectItemTable.projectId] = project.id
                    it[ProjectItemTable.type] = 2
                    it[ProjectItemTable.slug] = "twin-$number"
                    it[ProjectItemTable.name] = "Twin $number"
                    it[ProjectItemTable.createdAt] = sharedInstant
                    it[ProjectItemTable.updatedAt] = sharedInstant
                }
            }
            id to sharedInstant
        }

        val orders = (1..10).map { reads.listItems(project.slug, ItemFilter()).map { item -> item.id } }

        // Expected from the instants the rows actually carry, not from the order
        // they were created in: two sequential writes landing on one microsecond
        // would be a legitimate tie, not a broken ordering.
        val expected = (twins + inSequence)
            .sortedWith(compareByDescending<Pair<Uuid, Instant>> { it.second }.thenBy { it.first.toString() })
            .map { it.first }
        orders.forEach { order ->
            assertEquals(expected, order, "every identical call must return the same order")
        }
    }
}
