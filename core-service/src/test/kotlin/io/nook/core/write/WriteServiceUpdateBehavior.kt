package io.nook.core.write

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.CreateRelease
import io.nook.contract.ErrorCode
import io.nook.contract.FieldChange
import io.nook.contract.ItemStatus
import io.nook.contract.ItemType
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateItem
import io.nook.core.catalog.CatalogBehavior
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * update_item against the running service: partial updates, slug stability
 * and explicit changes, reparenting, the status vocabulary, type-change
 * rules, and the guarantee that a failed call changes nothing.
 */
abstract class WriteServiceUpdateBehavior : CatalogBehavior() {

    private fun assertFailsWithCode(code: ErrorCode, block: () -> Unit): StructuredErrorException {
        val failure = assertFailsWith<StructuredErrorException> { block() }
        assertEquals(code, failure.error.code)
        return failure
    }

    private fun reload(projectSlug: String, itemRef: String) = readItem(db, projectSlug, itemRef)

    @Test
    fun `renaming changes the name but never the slug, by slug or by id`() {
        val project = service.createProject(CreateProject("Rename Stability"))
        val item = service.createItem(project.slug, CreateItem(type = "task", name = "Add search"))

        val renamed = service.updateItem(
            project.slug, "add-search",
            UpdateItem(name = FieldChange.Set("Improve search")),
        )
        assertEquals("Improve search", renamed.name)
        assertEquals("add-search", renamed.slug)
        assertEquals(item.id, renamed.id)
        assertEquals(item.status, renamed.status)
        assertEquals(item.description, renamed.description)

        val renamedById = service.updateItem(
            project.slug, item.id.toString(),
            UpdateItem(name = FieldChange.Set("Improve search more")),
        )
        assertEquals("Improve search more", renamedById.name)
        assertEquals("add-search", renamedById.slug)
    }

    @Test
    fun `an explicit slug change moves resolution to the new slug and retires the old`() {
        val project = service.createProject(CreateProject("Slug Move"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Add search"))

        val moved = service.updateItem(
            project.slug, "add-search",
            UpdateItem(slug = FieldChange.Set("search-entry")),
        )
        assertEquals("search-entry", moved.slug)

        // A subsequent operation resolves the new slug, and no longer the old.
        assertEquals(moved.id, service.updateItem(project.slug, "search-entry", UpdateItem()).id)
        assertFailsWithCode(ErrorCode.NOT_FOUND) {
            service.updateItem(project.slug, "add-search", UpdateItem())
        }
    }

    @Test
    fun `a taken explicit slug conflicts on create, and supplying an item's own slug is a no-op`() {
        val project = service.createProject(CreateProject("Slug Claims"))
        val first = service.createItem(project.slug, CreateItem(type = "task", name = "Core", slug = "search-core"))
        assertFailsWithCode(ErrorCode.CONFLICT) {
            service.createItem(project.slug, CreateItem(type = "task", name = "Other", slug = "search-core"))
        }
        val unchanged = service.updateItem(
            project.slug, "search-core",
            UpdateItem(slug = FieldChange.Set("search-core")),
        )
        assertEquals(first.id, unchanged.id)
        assertEquals("search-core", unchanged.slug)
    }

    @Test
    fun `statuses move freely in the vocabulary, cascade to nothing, and reject outside values`() {
        val project = service.createProject(CreateProject("Status Moves"))
        service.createItem(project.slug, CreateItem(type = "epic", name = "Search core"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Leaf one", parentRef = "search-core"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Leaf two", parentRef = "search-core"))
        service.updateItem(project.slug, "leaf-one", UpdateItem(status = FieldChange.Set("in_progress")))
        service.updateItem(project.slug, "leaf-two", UpdateItem(status = FieldChange.Set("in_progress")))

        val epicDone = service.updateItem(
            project.slug, "search-core",
            UpdateItem(status = FieldChange.Set("done")),
        )
        assertEquals(ItemStatus.DONE, epicDone.status)
        assertEquals(ItemStatus.IN_PROGRESS, reload(project.slug, "leaf-one").status)
        assertEquals(ItemStatus.IN_PROGRESS, reload(project.slug, "leaf-two").status)

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(project.slug, "leaf-one", UpdateItem(status = FieldChange.Set("blocked")))
        }

        service.updateItem(project.slug, "leaf-two", UpdateItem(status = FieldChange.Set("cancelled")))
        val revived = service.updateItem(
            project.slug, "leaf-two",
            UpdateItem(
                status = FieldChange.Set("todo"),
                description = FieldChange.Set("back on the menu"),
            ),
        )
        assertEquals(ItemStatus.TODO, revived.status)
        assertEquals("back on the menu", revived.description)
    }

    @Test
    fun `a done task reopens and its row never ceases to exist`() {
        val project = service.createProject(CreateProject("Nothing Dies"))
        val task = service.createItem(project.slug, CreateItem(type = "task", name = "Persistent task"))
        service.updateItem(project.slug, task.slug, UpdateItem(status = FieldChange.Set("done")))
        val reopened = service.updateItem(project.slug, task.slug, UpdateItem(status = FieldChange.Set("todo")))
        assertEquals(ItemStatus.TODO, reopened.status)
        service.updateItem(project.slug, task.slug, UpdateItem(status = FieldChange.Set("cancelled")))
        assertEquals(task.id, reload(project.slug, task.slug).id)
    }

    @Test
    fun `leaf types interchange freely but an edge-holding leaf cannot become an epic until cleared`() {
        val project = service.createProject(CreateProject("Leaf Conversions"))
        val bug = service.createItem(project.slug, CreateItem(type = "bug", name = "Blocking bug"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Waiting task"))
        service.updateItem(
            project.slug, "waiting-task",
            UpdateItem(blockedBy = FieldChange.Set(listOf("blocking-bug"))),
        )

        val chore = service.updateItem(project.slug, bug.slug, UpdateItem(type = FieldChange.Set("chore")))
        assertEquals(ItemType.CHORE, chore.type)

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(project.slug, bug.slug, UpdateItem(type = FieldChange.Set("epic")))
        }

        service.updateItem(project.slug, "waiting-task", UpdateItem(blockedBy = FieldChange.Set(emptyList())))
        val promoted = service.updateItem(project.slug, bug.slug, UpdateItem(type = FieldChange.Set("epic")))
        assertEquals(ItemType.EPIC, promoted.type)
    }

    // Two guards stop an epic becoming a leaf, and each gets a fixture where it
    // is the only thing that could refuse the call. Setting up children and a
    // release together would pass while either one alone still worked, which
    // reads the same as passing while both do.

    @Test
    fun `an epic with children cannot become a leaf until they are reparented`() {
        val project = service.createProject(CreateProject("Epic Demotion Children"))
        val epic = service.createItem(project.slug, CreateItem(type = "epic", name = "Parent epic"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Child task", parentRef = epic.slug))

        val failure = assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(project.slug, epic.slug, UpdateItem(type = FieldChange.Set("task")))
        }
        assertEquals(true, failure.error.message.contains("child"), failure.error.message)

        service.updateItem(project.slug, "child-task", UpdateItem(parentRef = FieldChange.Set(null)))
        val demoted = service.updateItem(project.slug, epic.slug, UpdateItem(type = FieldChange.Set("task")))
        assertEquals(ItemType.TASK, demoted.type)
    }

    @Test
    fun `an epic in a release cannot become a leaf until it is unassigned`() {
        val project = service.createProject(CreateProject("Epic Demotion Release"))
        val epic = service.createItem(project.slug, CreateItem(type = "epic", name = "Released epic"))
        service.createRelease(project.slug, CreateRelease("v1"))
        service.updateItem(project.slug, epic.slug, UpdateItem(releaseRef = FieldChange.Set("v1")))

        val failure = assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(project.slug, epic.slug, UpdateItem(type = FieldChange.Set("task")))
        }
        assertEquals(true, failure.error.message.contains("release"), failure.error.message)

        service.updateItem(project.slug, epic.slug, UpdateItem(releaseRef = FieldChange.Set(null)))
        val demoted = service.updateItem(project.slug, epic.slug, UpdateItem(type = FieldChange.Set("task")))
        assertEquals(ItemType.TASK, demoted.type)
    }

    @Test
    fun `reparenting keeps the slug and blockers, clearing goes project-level, and same-parent is accepted`() {
        val project = service.createProject(CreateProject("Reparenting"))
        val epicA = service.createItem(project.slug, CreateItem(type = "epic", name = "Epic A"))
        val epicB = service.createItem(project.slug, CreateItem(type = "epic", name = "Epic B"))
        val blocker = service.createItem(project.slug, CreateItem(type = "task", name = "The blocker"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Add search", parentRef = epicA.slug))
        val withBlocker = service.updateItem(
            project.slug, "add-search",
            UpdateItem(blockedBy = FieldChange.Set(listOf(blocker.slug))),
        )
        assertEquals(setOf(blocker.id), withBlocker.blockedBy)

        val moved = service.updateItem(
            project.slug, "add-search",
            UpdateItem(parentRef = FieldChange.Set(epicB.slug)),
        )
        assertEquals(epicB.id, moved.parentId)
        assertEquals("add-search", moved.slug)
        assertEquals(setOf(blocker.id), moved.blockedBy)

        val cleared = service.updateItem(
            project.slug, "add-search",
            UpdateItem(parentRef = FieldChange.Set(null)),
        )
        assertNull(cleared.parentId)

        service.updateItem(project.slug, "add-search", UpdateItem(parentRef = FieldChange.Set(epicB.slug)))
        val samePlace = service.updateItem(
            project.slug, "add-search",
            UpdateItem(parentRef = FieldChange.Set(epicB.slug)),
        )
        assertEquals(epicB.id, samePlace.parentId)
    }

    @Test
    fun `a failed update is one structured error and changes nothing`() {
        val project = service.createProject(CreateProject("Atomic Failure"))
        val item = service.createItem(project.slug, CreateItem(type = "task", name = "Add search"))

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(
                project.slug, item.slug,
                UpdateItem(
                    name = FieldChange.Set("Renamed anyway?"),
                    status = FieldChange.Set("finished"),
                ),
            )
        }

        val untouched = reload(project.slug, item.slug)
        assertEquals("Add search", untouched.name)
        assertEquals(ItemStatus.TODO, untouched.status)
    }
}

class WriteServiceUpdateInProcessTest : WriteServiceUpdateBehavior() {
    override val reach = Reach.IN_PROCESS
}

class WriteServiceUpdateAcrossConnectionTest : WriteServiceUpdateBehavior() {
    override val reach = Reach.ACROSS_THE_CONNECTION
}
