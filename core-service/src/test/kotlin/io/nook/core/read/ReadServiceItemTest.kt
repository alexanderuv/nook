package io.nook.core.read

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.ErrorCode
import io.nook.contract.ItemStatus
import io.nook.contract.ItemType
import io.nook.contract.SetItemBlockedBy
import io.nook.contract.StructuredErrorException
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.write.WriteService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Fetching one item: how a reference is resolved, what comes back, and what
 * happens when the reference names something out of reach.
 */
class ReadServiceItemTest {

    private companion object {
        val db by lazy { Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase()) }
        val writes by lazy { WriteService(db) }
        val reads by lazy { ReadService(db) }
    }

    private fun assertNotFound(block: () -> Unit) {
        val failure = assertFailsWith<StructuredErrorException> { block() }
        assertEquals(ErrorCode.NOT_FOUND, failure.error.code)
    }

    @Test
    fun `an item resolves by handle and by id, and never across a project boundary`() {
        val here = writes.createProject(CreateProject("Fetch Here"))
        val elsewhere = writes.createProject(CreateProject("Fetch Elsewhere"))
        val item = writes.createItem(here.slug, CreateItem(type = "task", name = "Add search"))
        val foreign = writes.createItem(elsewhere.slug, CreateItem(type = "task", name = "Not yours"))

        assertEquals(item, reads.getItem(here.slug, "add-search"))
        assertEquals(item, reads.getItem(here.slug, item.id.toString()))
        assertNotFound { reads.getItem(here.slug, foreign.id.toString()) }
        assertNotFound { reads.getItem(here.slug, Uuid.random().toString()) }
    }

    @Test
    fun `a fetched item carries every field, its parent, and its whole blocker set`() {
        val project = writes.createProject(CreateProject("Full Entity"))
        val epic = writes.createItem(project.slug, CreateItem(type = "epic", name = "Search"))
        val firstBlocker = writes.createItem(project.slug, CreateItem(type = "task", name = "Blocker one"))
        val secondBlocker = writes.createItem(project.slug, CreateItem(type = "task", name = "Blocker two"))
        writes.createItem(
            project.slug,
            CreateItem(
                type = "task",
                name = "Add search",
                description = "The searchable one",
                parentRef = "search",
            ),
        )
        writes.setItemBlockedBy(
            project.slug, "add-search",
            SetItemBlockedBy(listOf("blocker-one", "blocker-two")),
        )

        val fetched = reads.getItem(project.slug, "add-search")

        assertEquals(ItemType.TASK, fetched.type)
        assertEquals("Add search", fetched.name)
        assertEquals("add-search", fetched.slug)
        assertEquals("The searchable one", fetched.description)
        assertEquals(ItemStatus.TODO, fetched.status)
        assertEquals(epic.id, fetched.parentId)
        assertEquals(project.id, fetched.projectId)
        assertEquals(setOf(firstBlocker.id, secondBlocker.id), fetched.blockedBy)
        assertNotNull(fetched.createdAt)
        assertNotNull(fetched.updatedAt)
    }

    @Test
    fun `a handle taken over by a new item resolves to it, and the old id to nothing`() {
        val project = writes.createProject(CreateProject("Handle Handover"))
        val gone = writes.createItem(project.slug, CreateItem(type = "task", name = "Add search"))
        writes.deleteItem(project.slug, "add-search")
        val replacement = writes.createItem(
            project.slug,
            CreateItem(type = "bug", name = "Add search", slug = "add-search"),
        )

        assertEquals(replacement.id, reads.getItem(project.slug, "add-search").id)
        assertNotFound { reads.getItem(project.slug, gone.id.toString()) }
    }
}
