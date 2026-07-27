package io.nook.core.write

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.CreateRelease
import io.nook.contract.ErrorCode
import io.nook.contract.FieldChange
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateItem
import io.nook.core.db.DocumentTable
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.ItemDependencyTable
import io.nook.core.db.ProjectItemTable
import io.nook.core.db.ProjectTable
import io.nook.core.db.ReleaseTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * The deletion contract, rule by rule, through the service:
 *
 *  - the row is removed, not marked: nothing about it survives anywhere;
 *  - deleting an epic takes its children, and a project takes everything in it,
 *    including the blocker edges reaching into what went;
 *  - the handle it held is free again at once;
 *  - deleting is independent of status, in both directions;
 *  - a second delete is `not_found`, like any other reference to nothing;
 *  - and there is no way back — the surface offers nothing that restores.
 *
 * The counts here are read from the tables directly, because after a delete
 * there is nothing left for an operation to return.
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

    private fun itemsWithId(id: Uuid): Long = transaction(db) {
        ProjectItemTable.selectAll().where { ProjectItemTable.id eq id }.count()
    }

    private fun projectsWithId(id: Uuid): Long = transaction(db) {
        ProjectTable.selectAll().where { ProjectTable.id eq id }.count()
    }

    private fun releasesWithId(id: Uuid): Long = transaction(db) {
        ReleaseTable.selectAll().where { ReleaseTable.id eq id }.count()
    }

    private fun edgesTouching(id: Uuid): Long = transaction(db) {
        ItemDependencyTable.selectAll()
            .where { (ItemDependencyTable.itemId eq id) or (ItemDependencyTable.dependsOnId eq id) }
            .count()
    }

    @Test
    fun `a deleted item is gone from the store, not marked in it`() {
        val project = service.createProject(CreateProject("Gone Means Gone"))
        val item = service.createItem(project.slug, CreateItem(type = "task", name = "Add search"))

        service.deleteItem(project.slug, "add-search")

        assertEquals(0L, itemsWithId(item.id), "the row must be removed")
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
            assertEquals(0L, itemsWithId(id), "every row of the branch must be gone")
        }
        assertEquals(1L, itemsWithId(outsider.id), "an item outside the branch must be untouched")
    }

    @Test
    fun `a deleted item takes its blocker edges with it, from both ends`() {
        val project = service.createProject(CreateProject("Edges Go Too"))
        val blocker = service.createItem(project.slug, CreateItem(type = "task", name = "Blocker"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Blocked"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Downstream"))
        service.updateItem(project.slug, "blocked", UpdateItem(blockedBy = FieldChange.Set(listOf("blocker"))))
        service.updateItem(project.slug, "downstream", UpdateItem(blockedBy = FieldChange.Set(listOf("blocker"))))

        service.deleteItem(project.slug, "blocker")

        assertEquals(0L, edgesTouching(blocker.id), "no edge may point at a row that is gone")
        // The items it was holding up remain, now unblocked.
        assertEquals(emptySet(), readItem(db, project.slug, "blocked").blockedBy)
        assertEquals(emptySet(), readItem(db, project.slug, "downstream").blockedBy)
    }

    @Test
    fun `a handle freed by a delete is available again at once`() {
        val project = service.createProject(CreateProject("Handles Come Back"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Add search"))
        service.deleteItem(project.slug, "add-search")

        val replacement = service.createItem(
            project.slug,
            CreateItem(type = "bug", name = "Add search", slug = "add-search"),
        )

        assertEquals("add-search", replacement.slug, "the freed handle must be taken as asked, never suffixed")
        assertEquals(replacement.id, readItem(db, project.slug, "add-search").id)
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

        assertEquals(0L, itemsWithId(cancelled.id))
        assertEquals(0L, itemsWithId(stillTodo.id))
    }

    @Test
    fun `deleting a project takes everything inside it in one transaction`() {
        val project = service.createProject(CreateProject("Doomed Project"))
        val release = service.createRelease(project.slug, CreateRelease("v1"))
        val epic = service.createItem(project.slug, CreateItem(type = "epic", name = "Epic"))
        val leaf = service.createItem(project.slug, CreateItem(type = "task", name = "Leaf", parentRef = "epic"))
        val blocker = service.createItem(project.slug, CreateItem(type = "bug", name = "Loose"))
        service.updateItem(project.slug, "leaf", UpdateItem(blockedBy = FieldChange.Set(listOf("loose"))))

        service.deleteProject(project.slug)

        assertEquals(0L, projectsWithId(project.id))
        assertEquals(0L, releasesWithId(release.id))
        listOf(epic.id, leaf.id, blocker.id).forEach { id ->
            assertEquals(0L, itemsWithId(id), "nothing in the project may survive it")
        }
        assertEquals(0L, edgesTouching(leaf.id))
        assertFailsWithCode(ErrorCode.NOT_FOUND) {
            service.createItem(project.slug, CreateItem(type = "task", name = "Too late"))
        }
    }

    @Test
    fun `nothing survives a project deleted while another caller writes into it`() {
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

        val leftBehind = transaction(db) {
            ProjectItemTable.selectAll().where { ProjectItemTable.projectId eq project.id }.count()
        }
        assertEquals(0L, leftBehind, "no item may outlive the deletion of the project holding it")
        assertEquals(0L, projectsWithId(project.id))
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

    /**
     * The documents attached to what is deleted go with it. Nothing writes a
     * document yet, so these rows are inserted directly — but the foreign key
     * that would otherwise refuse the delete is on already, and it is `NO
     * ACTION`: without the write path removing them first, deleting an item
     * with a document attached comes back as a fault in the service.
     *
     * A document row is a pointer; the content lives in git and is not this
     * operation's to remove. That reach is the same one deleting a project
     * already has, and the git side is deferred deliberately (see docs/04).
     */
    @Test
    fun `a deleted item takes its documents, and an epic takes its children's too`() {
        val project = service.createProject(CreateProject("Documents Go Too"))
        val epic = service.createItem(project.slug, CreateItem(type = "epic", name = "Documented epic"))
        val child = service.createItem(
            project.slug,
            CreateItem(type = "task", name = "Documented child", parentRef = epic.slug),
        )
        val bystander = service.createItem(project.slug, CreateItem(type = "task", name = "Bystander"))

        val onEpic = attachDocument(project.id, epic.id, "/epics/documented-epic/plan.md")
        val onChild = attachDocument(project.id, child.id, "/epics/documented-epic/child/plan.md")
        val onBystander = attachDocument(project.id, bystander.id, "/tasks/bystander/plan.md")
        val projectLevel = attachDocument(project.id, null, "/architecture.md")

        service.deleteItem(project.slug, epic.slug)

        assertEquals(0L, documentsWithId(onEpic), "the epic's own document must go with it")
        assertEquals(0L, documentsWithId(onChild), "a child's document must go with the child")
        assertEquals(1L, documentsWithId(onBystander), "an unrelated item's document must be untouched")
        assertEquals(1L, documentsWithId(projectLevel), "a project-level document belongs to no item")
    }

    @Test
    fun `deleting a project takes its documents with it, attached or not`() {
        val project = service.createProject(CreateProject("Documented Project"))
        val item = service.createItem(project.slug, CreateItem(type = "task", name = "Documented task"))
        val attached = attachDocument(project.id, item.id, "/doomed/tasks/plan.md")
        val projectLevel = attachDocument(project.id, null, "/doomed/architecture.md")

        service.deleteProject(project.slug)

        assertEquals(0L, documentsWithId(attached))
        assertEquals(0L, documentsWithId(projectLevel))
    }

    /** A document row, written directly: no operation creates one yet. */
    private fun attachDocument(projectId: Uuid, itemId: Uuid?, path: String): Uuid {
        val id = Uuid.random()
        transaction(db) {
            DocumentTable.insert {
                it[DocumentTable.id] = id
                it[DocumentTable.projectId] = projectId
                it[DocumentTable.itemId] = itemId
                it[DocumentTable.kind] = 2
                it[DocumentTable.name] = "plan"
                it[DocumentTable.path] = path
            }
        }
        return id
    }

    private fun documentsWithId(id: Uuid): Long = transaction(db) {
        DocumentTable.selectAll().where { DocumentTable.id eq id }.count()
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
