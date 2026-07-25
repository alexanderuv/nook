package io.nook.core.write

import io.nook.contract.ErrorCode
import io.nook.contract.ItemStatus
import io.nook.contract.ItemType
import io.nook.contract.StructuredErrorException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * update_item against the running service: partial updates, slug stability
 * and explicit changes, reparenting, the status vocabulary, type-change
 * rules, and the guarantee that a failed call changes nothing.
 */
class WriteServiceUpdateTest {

    private companion object {
        val db by lazy { Database.connect(io.nook.core.db.EmbeddedPostgresSupport.freshMigratedDatabase()) }
        val service by lazy { WriteService(db) }
    }

    private fun assertFailsWithCode(code: ErrorCode, block: () -> Unit): StructuredErrorException {
        val failure = assertFailsWith<StructuredErrorException> { block() }
        assertEquals(code, failure.error.code)
        return failure
    }

    private fun reload(projectSlug: String, itemRef: String) = readItem(db, projectSlug, itemRef)

    @Test
    fun `renaming changes the name but never the slug, by slug or by id`() {
        val project = service.createProject("Rename Stability")
        val item = service.createItem(project.slug, type = "task", name = "Add search")

        val renamed = service.updateItem(
            project.slug, "add-search",
            name = FieldChange.Set("Improve search"),
        )
        assertEquals("Improve search", renamed.name)
        assertEquals("add-search", renamed.slug)
        assertEquals(item.id, renamed.id)
        assertEquals(item.status, renamed.status)
        assertEquals(item.description, renamed.description)

        val renamedById = service.updateItem(
            project.slug, item.id.toString(),
            name = FieldChange.Set("Improve search more"),
        )
        assertEquals("Improve search more", renamedById.name)
        assertEquals("add-search", renamedById.slug)
    }

    @Test
    fun `an explicit slug change moves resolution to the new slug and retires the old`() {
        val project = service.createProject("Slug Move")
        service.createItem(project.slug, type = "task", name = "Add search")

        val moved = service.updateItem(
            project.slug, "add-search",
            slug = FieldChange.Set("search-entry"),
        )
        assertEquals("search-entry", moved.slug)

        // A subsequent operation resolves the new slug, and no longer the old.
        assertEquals(moved.id, service.updateItem(project.slug, "search-entry").id)
        assertFailsWithCode(ErrorCode.NOT_FOUND) { service.updateItem(project.slug, "add-search") }
    }

    @Test
    fun `a taken explicit slug conflicts on create, and supplying an item's own slug is a no-op`() {
        val project = service.createProject("Slug Claims")
        val first = service.createItem(project.slug, type = "task", name = "Core", slug = "search-core")
        assertFailsWithCode(ErrorCode.CONFLICT) {
            service.createItem(project.slug, type = "task", name = "Other", slug = "search-core")
        }
        val unchanged = service.updateItem(
            project.slug, "search-core",
            slug = FieldChange.Set("search-core"),
        )
        assertEquals(first.id, unchanged.id)
        assertEquals("search-core", unchanged.slug)
    }

    @Test
    fun `statuses move freely in the vocabulary, cascade to nothing, and reject outside values`() {
        val project = service.createProject("Status Moves")
        service.createItem(project.slug, type = "epic", name = "Search core")
        service.createItem(project.slug, type = "task", name = "Leaf one", parentRef = "search-core")
        service.createItem(project.slug, type = "task", name = "Leaf two", parentRef = "search-core")
        service.updateItem(project.slug, "leaf-one", status = FieldChange.Set("in_progress"))
        service.updateItem(project.slug, "leaf-two", status = FieldChange.Set("in_progress"))

        val epicDone = service.updateItem(project.slug, "search-core", status = FieldChange.Set("done"))
        assertEquals(ItemStatus.DONE, epicDone.status)
        assertEquals(ItemStatus.IN_PROGRESS, reload(project.slug, "leaf-one").status)
        assertEquals(ItemStatus.IN_PROGRESS, reload(project.slug, "leaf-two").status)

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(project.slug, "leaf-one", status = FieldChange.Set("blocked"))
        }

        service.updateItem(project.slug, "leaf-two", status = FieldChange.Set("cancelled"))
        val revived = service.updateItem(
            project.slug, "leaf-two",
            status = FieldChange.Set("todo"),
            description = FieldChange.Set("back on the menu"),
        )
        assertEquals(ItemStatus.TODO, revived.status)
        assertEquals("back on the menu", revived.description)
    }

    @Test
    fun `a done task reopens and its row never ceases to exist`() {
        val project = service.createProject("Nothing Dies")
        val task = service.createItem(project.slug, type = "task", name = "Persistent task")
        service.updateItem(project.slug, task.slug, status = FieldChange.Set("done"))
        val reopened = service.updateItem(project.slug, task.slug, status = FieldChange.Set("todo"))
        assertEquals(ItemStatus.TODO, reopened.status)
        service.updateItem(project.slug, task.slug, status = FieldChange.Set("cancelled"))
        assertEquals(task.id, reload(project.slug, task.slug).id)
    }

    @Test
    fun `leaf types interchange freely but an edge-holding leaf cannot become an epic until cleared`() {
        val project = service.createProject("Leaf Conversions")
        val bug = service.createItem(project.slug, type = "bug", name = "Blocking bug")
        service.createItem(project.slug, type = "task", name = "Waiting task")
        service.setItemBlockedBy(project.slug, "waiting-task", listOf("blocking-bug"))

        val chore = service.updateItem(project.slug, bug.slug, type = FieldChange.Set("chore"))
        assertEquals(ItemType.CHORE, chore.type)

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(project.slug, bug.slug, type = FieldChange.Set("epic"))
        }

        service.setItemBlockedBy(project.slug, "waiting-task", emptyList())
        val promoted = service.updateItem(project.slug, bug.slug, type = FieldChange.Set("epic"))
        assertEquals(ItemType.EPIC, promoted.type)
    }

    @Test
    fun `an epic with children or a release assignment cannot become a leaf until detached`() {
        val project = service.createProject("Epic Demotion")
        val epic = service.createItem(project.slug, type = "epic", name = "Loaded epic")
        service.createItem(project.slug, type = "task", name = "Child task", parentRef = epic.slug)
        service.createRelease(project.slug, "v1")
        service.assignEpicToRelease(project.slug, epic.slug, "v1")

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(project.slug, epic.slug, type = FieldChange.Set("task"))
        }

        service.updateItem(project.slug, "child-task", parentRef = FieldChange.Set(null))
        service.assignEpicToRelease(project.slug, epic.slug, null)
        val demoted = service.updateItem(project.slug, epic.slug, type = FieldChange.Set("task"))
        assertEquals(ItemType.TASK, demoted.type)
    }

    @Test
    fun `reparenting keeps the slug and blockers, clearing goes project-level, and same-parent is accepted`() {
        val project = service.createProject("Reparenting")
        val epicA = service.createItem(project.slug, type = "epic", name = "Epic A")
        val epicB = service.createItem(project.slug, type = "epic", name = "Epic B")
        val blocker = service.createItem(project.slug, type = "task", name = "The blocker")
        service.createItem(project.slug, type = "task", name = "Add search", parentRef = epicA.slug)
        val withBlocker = service.setItemBlockedBy(project.slug, "add-search", listOf(blocker.slug))
        assertEquals(setOf(blocker.id), withBlocker.blockedBy)

        val moved = service.updateItem(project.slug, "add-search", parentRef = FieldChange.Set(epicB.slug))
        assertEquals(epicB.id, moved.parentId)
        assertEquals("add-search", moved.slug)
        assertEquals(setOf(blocker.id), moved.blockedBy)

        val cleared = service.updateItem(project.slug, "add-search", parentRef = FieldChange.Set(null))
        assertNull(cleared.parentId)

        service.updateItem(project.slug, "add-search", parentRef = FieldChange.Set(epicB.slug))
        val samePlace = service.updateItem(project.slug, "add-search", parentRef = FieldChange.Set(epicB.slug))
        assertEquals(epicB.id, samePlace.parentId)
    }

    @Test
    fun `a failed update is one structured error and changes nothing`() {
        val project = service.createProject("Atomic Failure")
        val item = service.createItem(project.slug, type = "task", name = "Add search")

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(
                project.slug, item.slug,
                name = FieldChange.Set("Renamed anyway?"),
                status = FieldChange.Set("finished"),
            )
        }

        val untouched = reload(project.slug, item.slug)
        assertEquals("Add search", untouched.name)
        assertEquals(ItemStatus.TODO, untouched.status)
    }
}
