package io.nook.core.read

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
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
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Fetching one item: how a reference is resolved, what comes back, and what
 * happens when the reference names something out of reach.
 */
abstract class ReadServiceItemBehavior : CatalogBehavior() {

    private fun assertNotFound(block: () -> Unit) {
        val failure = assertFailsWith<StructuredErrorException> { block() }
        assertEquals(ErrorCode.NOT_FOUND, failure.error.code)
    }

    @Test
    fun `an item resolves by handle and by id, and never across a project boundary`() {
        val here = service.createProject(CreateProject("Fetch Here"))
        val elsewhere = service.createProject(CreateProject("Fetch Elsewhere"))
        val item = service.createItem(here.slug, CreateItem(type = "task", name = "Add search"))
        val foreign = service.createItem(elsewhere.slug, CreateItem(type = "task", name = "Not yours"))

        assertEquals(item, service.getItem(here.slug, "add-search"))
        assertEquals(item, service.getItem(here.slug, item.id.toString()))
        assertNotFound { service.getItem(here.slug, foreign.id.toString()) }
        assertNotFound { service.getItem(here.slug, Uuid.random().toString()) }
    }

    @Test
    fun `a fetched item carries every field, its parent, and its whole blocker set`() {
        val project = service.createProject(CreateProject("Full Entity"))
        val epic = service.createItem(project.slug, CreateItem(type = "epic", name = "Search"))
        val firstBlocker = service.createItem(project.slug, CreateItem(type = "task", name = "Blocker one"))
        val secondBlocker = service.createItem(project.slug, CreateItem(type = "task", name = "Blocker two"))
        val item = service.createItem(
            project.slug,
            CreateItem(
                type = "task",
                name = "Add search",
                description = "The searchable one",
                parentRef = "search",
            ),
        )
        service.updateItem(
            project.slug, "add-search",
            UpdateItem(blockedBy = FieldChange.Set(listOf("blocker-one", "blocker-two"))),
        )

        val fetched = service.getItem(project.slug, "add-search")

        assertEquals(ItemType.TASK, fetched.type)
        assertEquals("Add search", fetched.name)
        assertEquals("add-search", fetched.slug)
        assertEquals("The searchable one", fetched.description)
        assertEquals(ItemStatus.TODO, fetched.status)
        assertEquals(epic.id, fetched.parentId)
        assertEquals(project.id, fetched.projectId)
        assertEquals(setOf(firstBlocker.id, secondBlocker.id), fetched.blockedBy)
        // Both timestamps are non-nullable, so their presence says nothing. What
        // is worth asserting is that a read hands back the moments the write
        // recorded, and that the blocker set arriving later moved only one of them.
        assertEquals(item.createdAt, fetched.createdAt)
        assertTrue(
            fetched.updatedAt >= fetched.createdAt,
            "an item cannot have been updated before it existed",
        )
    }

    @Test
    fun `a handle taken over by a new item resolves to it, and the old id to nothing`() {
        val project = service.createProject(CreateProject("Handle Handover"))
        val gone = service.createItem(project.slug, CreateItem(type = "task", name = "Add search"))
        service.deleteItem(project.slug, "add-search")
        val replacement = service.createItem(
            project.slug,
            CreateItem(type = "bug", name = "Add search", slug = "add-search"),
        )

        assertEquals(replacement.id, service.getItem(project.slug, "add-search").id)
        assertNotFound { service.getItem(project.slug, gone.id.toString()) }
    }
}

class ReadServiceItemInProcessTest : ReadServiceItemBehavior() {
    override val reach = Reach.IN_PROCESS
}

class ReadServiceItemAcrossConnectionTest : ReadServiceItemBehavior() {
    override val reach = Reach.ACROSS_THE_CONNECTION
}
