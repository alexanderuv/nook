package io.nook.core.write

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.CreateRelease
import io.nook.contract.ErrorCode
import io.nook.contract.FieldChange
import io.nook.contract.ItemStatus
import io.nook.contract.SetItemBlockedBy
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateItem
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.ItemDependencyTable
import io.nook.core.db.ProjectItemTable
import io.nook.core.db.ProjectTable
import io.nook.core.db.ReleaseTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * The deletion contract, rule by rule, through the service:
 *
 *  - nothing is physically removed — the row stays, carrying its mark;
 *  - the mark reaches no caller: every reference to a marked row is not_found,
 *    by handle and by id alike;
 *  - deleting an epic takes its children, and a project takes everything in it;
 *  - a freed handle is available again at once;
 *  - deleting is independent of status, in both directions;
 *  - and there is no way back — the surface offers nothing that clears a mark.
 *
 * What the reads do with a deleted row is the read path's own subject; the rows
 * seen here are read from the tables directly, which is the only remaining way
 * to observe that a deleted row still exists at all.
 */
class WriteServiceDeleteTest {

    private companion object {
        val db by lazy { Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase()) }
        val service by lazy { WriteService(db) }
    }

    private fun assertFailsWithCode(code: ErrorCode, block: () -> Unit) {
        val failure = assertFailsWith<StructuredErrorException> { block() }
        assertEquals(code, failure.error.code)
    }

    /** The stored row behind an id, mark included — bypassing every live-only rule. */
    private fun storedItem(id: Uuid): ResultRow? = transaction(db) {
        ProjectItemTable.selectAll().where { ProjectItemTable.id eq id }.firstOrNull()
    }

    private fun storedProject(id: Uuid): ResultRow? = transaction(db) {
        ProjectTable.selectAll().where { ProjectTable.id eq id }.firstOrNull()
    }

    private fun storedRelease(id: Uuid): ResultRow? = transaction(db) {
        ReleaseTable.selectAll().where { ReleaseTable.id eq id }.firstOrNull()
    }

    @Test
    fun `a deleted item stays in the store and leaves every caller's reach`() {
        val project = service.createProject(CreateProject("Survives Deletion"))
        val item = service.createItem(project.slug, CreateItem(type = "task", name = "Add search"))

        service.deleteItem(project.slug, "add-search")

        val stored = assertNotNull(storedItem(item.id), "the row must survive the delete")
        assertNotNull(stored[ProjectItemTable.deletedAt], "the surviving row must carry the mark")
        assertEquals("add-search", stored[ProjectItemTable.slug], "the row keeps the handle it held")

        assertFailsWithCode(ErrorCode.NOT_FOUND) {
            service.updateItem(project.slug, "add-search", UpdateItem(name = FieldChange.Set("Renamed")))
        }
        assertFailsWithCode(ErrorCode.NOT_FOUND) {
            service.updateItem(project.slug, item.id.toString(), UpdateItem(name = FieldChange.Set("Renamed")))
        }
    }

    @Test
    fun `deleting an item a second time is not found, by handle and by id`() {
        val project = service.createProject(CreateProject("Deleted Twice"))
        val item = service.createItem(project.slug, CreateItem(type = "task", name = "Once only"))

        service.deleteItem(project.slug, "once-only")

        assertFailsWithCode(ErrorCode.NOT_FOUND) { service.deleteItem(project.slug, "once-only") }
        assertFailsWithCode(ErrorCode.NOT_FOUND) { service.deleteItem(project.slug, item.id.toString()) }
    }

    @Test
    fun `deleting an epic takes its four children with it`() {
        val project = service.createProject(CreateProject("Whole Branch"))
        val epic = service.createItem(project.slug, CreateItem(type = "epic", name = "Search"))
        val children = (1..4).map { number ->
            service.createItem(
                project.slug,
                CreateItem(type = "task", name = "Child $number", parentRef = "search"),
            )
        }
        val outsider = service.createItem(project.slug, CreateItem(type = "task", name = "Outsider"))

        service.deleteItem(project.slug, "search")

        (children.map { it.id } + epic.id).forEach { id ->
            assertNotNull(
                storedItem(id)?.get(ProjectItemTable.deletedAt),
                "every row of the branch must carry the mark",
            )
        }
        assertNull(
            storedItem(outsider.id)?.get(ProjectItemTable.deletedAt),
            "an item outside the branch must be untouched",
        )
    }

    @Test
    fun `a handle freed by a delete is available again at once`() {
        val project = service.createProject(CreateProject("Handles Come Back"))
        val gone = service.createItem(project.slug, CreateItem(type = "task", name = "Add search"))
        service.deleteItem(project.slug, "add-search")

        val replacement = service.createItem(
            project.slug,
            CreateItem(type = "bug", name = "Add search", slug = "add-search"),
        )

        assertEquals("add-search", replacement.slug, "the freed handle must be taken as asked, never suffixed")
        assertEquals(gone.slug, replacement.slug)
        assertEquals(
            replacement.id,
            transaction(db) { io.nook.core.store.resolveItem(project.id, "add-search") }[ProjectItemTable.id],
            "the handle must resolve to the live item, not the deleted one",
        )
    }

    @Test
    fun `a derived handle freed by a delete is reused without a suffix`() {
        val project = service.createProject(CreateProject("Derived Handles Come Back"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Add search"))
        service.deleteItem(project.slug, "add-search")

        val replacement = service.createItem(project.slug, CreateItem(type = "task", name = "Add search"))

        assertEquals("add-search", replacement.slug)
    }

    @Test
    fun `deleting neither reads nor writes a status`() {
        val project = service.createProject(CreateProject("Status Is Untouched"))
        val cancelled = service.createItem(project.slug, CreateItem(type = "task", name = "Abandoned"))
        service.updateItem(project.slug, "abandoned", UpdateItem(status = FieldChange.Set("cancelled")))
        val stillTodo = service.createItem(project.slug, CreateItem(type = "task", name = "Fresh"))

        // Independent in both directions: a cancelled item may be deleted, and a
        // todo one may be deleted without first being cancelled.
        service.deleteItem(project.slug, "abandoned")
        service.deleteItem(project.slug, "fresh")

        assertEquals(ItemStatus.CANCELLED.code, storedItem(cancelled.id)?.get(ProjectItemTable.status))
        assertEquals(ItemStatus.TODO.code, storedItem(stillTodo.id)?.get(ProjectItemTable.status))
    }

    @Test
    fun `deleting a project marks everything inside it in one transaction`() {
        val project = service.createProject(CreateProject("Doomed Project"))
        val release = service.createRelease(project.slug, CreateRelease("v1"))
        val epic = service.createItem(project.slug, CreateItem(type = "epic", name = "Epic"))
        val leaf = service.createItem(project.slug, CreateItem(type = "task", name = "Leaf", parentRef = "epic"))
        val loose = service.createItem(project.slug, CreateItem(type = "bug", name = "Loose"))

        service.deleteProject(project.slug)

        assertNotNull(storedProject(project.id)?.get(ProjectTable.deletedAt))
        assertNotNull(storedRelease(release.id)?.get(ReleaseTable.deletedAt))
        listOf(epic.id, leaf.id, loose.id).forEach { id ->
            assertNotNull(storedItem(id)?.get(ProjectItemTable.deletedAt), "nothing in the project may stay live")
        }
        assertFailsWithCode(ErrorCode.NOT_FOUND) {
            service.createItem(project.slug, CreateItem(type = "task", name = "Too late"))
        }
    }

    @Test
    fun `nothing stays live in a project deleted while another caller writes into it`() {
        val project = service.createProject(CreateProject("Deleted Mid-Write"))
        val creators = (1..4).map { worker ->
            Thread {
                repeat(25) { round ->
                    // Once the project is gone its reference stops resolving, so
                    // a refusal here is the expected end of the race, not a fault.
                    runCatching {
                        service.createItem(
                            project.slug,
                            CreateItem(type = "task", name = "Worker $worker round $round"),
                        )
                    }
                }
            }
        }

        creators.forEach { it.start() }
        service.deleteProject(project.slug)
        creators.forEach { it.join() }

        val stillLive = transaction(db) {
            ProjectItemTable.selectAll()
                .where { (ProjectItemTable.projectId eq project.id) and ProjectItemTable.deletedAt.isNull() }
                .count()
        }
        assertEquals(0L, stillLive, "no item may outlive the deletion of the project holding it")
    }

    @Test
    fun `a project handle freed by a delete is available again at once`() {
        service.createProject(CreateProject("Reusable Project", slug = "reusable-project"))
        service.deleteProject("reusable-project")

        val replacement = service.createProject(CreateProject("Reusable Project", slug = "reusable-project"))

        assertEquals("reusable-project", replacement.slug)
    }

    @Test
    fun `deleting a project a second time is not found`() {
        val project = service.createProject(CreateProject("Gone Once"))
        service.deleteProject(project.slug)

        assertFailsWithCode(ErrorCode.NOT_FOUND) { service.deleteProject(project.slug) }
        assertFailsWithCode(ErrorCode.NOT_FOUND) { service.deleteProject(project.id.toString()) }
    }

    @Test
    fun `a deleted blocker leaves its edge behind and stops constraining live work`() {
        val project = service.createProject(CreateProject("Edges Outlive Rows"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Blocker"))
        val blocked = service.createItem(project.slug, CreateItem(type = "task", name = "Blocked"))
        service.setItemBlockedBy(project.slug, "blocked", SetItemBlockedBy(listOf("blocker")))

        service.deleteItem(project.slug, "blocker")

        val edges = transaction(db) {
            ItemDependencyTable.selectAll()
                .where { ItemDependencyTable.itemId eq blocked.id }
                .map { it[ItemDependencyTable.dependsOnId] }
        }
        assertEquals(1, edges.size, "the edge is history and must survive the blocker's deletion")

        // The blocked item is now free of every live constraint, so a conversion
        // the surviving edge would otherwise refuse must succeed.
        val converted = service.updateItem(project.slug, "blocked", UpdateItem(type = FieldChange.Set("epic")))
        assertEquals("epic", converted.type.label)
    }

    @Test
    fun `an epic whose only child was deleted can become a leaf`() {
        val project = service.createProject(CreateProject("Emptied Epic"))
        service.createItem(project.slug, CreateItem(type = "epic", name = "Emptied"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Only child", parentRef = "emptied"))

        service.deleteItem(project.slug, "only-child")

        val converted = service.updateItem(project.slug, "emptied", UpdateItem(type = FieldChange.Set("task")))
        assertEquals("task", converted.type.label)
    }
}
