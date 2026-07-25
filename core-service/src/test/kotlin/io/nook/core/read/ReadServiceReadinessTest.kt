package io.nook.core.read

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.FieldChange
import io.nook.contract.ItemFilter
import io.nook.contract.SetItemBlockedBy
import io.nook.contract.UpdateItem
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.write.WriteService
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * What can be worked on: the live leaves that are todo with nothing unresolved
 * holding them up. A blocker counts as resolved when it is done, cancelled, or
 * deleted — gone work must not deadlock what sits behind it.
 */
class ReadServiceReadinessTest {

    private companion object {
        val db by lazy { Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase()) }
        val writes by lazy { WriteService(db) }
        val reads by lazy { ReadService(db) }
    }

    @Test
    fun `exactly the unblocked todo leaves come back, and nothing else`() {
        val project = writes.createProject(CreateProject("Ready Work"))
        writes.createItem(project.slug, CreateItem(type = "epic", name = "An epic"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Finished blocker"))
        writes.updateItem(project.slug, "finished-blocker", UpdateItem(status = FieldChange.Set("done")))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Deleted blocker"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Running blocker"))
        writes.updateItem(project.slug, "running-blocker", UpdateItem(status = FieldChange.Set("in_progress")))

        writes.createItem(project.slug, CreateItem(type = "task", name = "Free leaf"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Cleared leaf"))
        writes.setItemBlockedBy(
            project.slug, "cleared-leaf",
            SetItemBlockedBy(listOf("finished-blocker", "deleted-blocker")),
        )
        writes.createItem(project.slug, CreateItem(type = "task", name = "Held leaf"))
        writes.setItemBlockedBy(project.slug, "held-leaf", SetItemBlockedBy(listOf("running-blocker")))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Finished leaf"))
        writes.updateItem(project.slug, "finished-leaf", UpdateItem(status = FieldChange.Set("done")))

        writes.deleteItem(project.slug, "deleted-blocker")

        // Newest created first: the cleared leaf was created after the free one.
        // Absent, each for its own reason: the epic (a container), the held leaf
        // (an unresolved blocker), the finished leaf (not todo), the two
        // surviving blockers (done and in progress), and the deleted one.
        assertEquals(
            listOf("cleared-leaf", "free-leaf"),
            reads.getReadyItems(project.slug).map { it.slug },
        )
    }

    @Test
    fun `a project whose every leaf is done offers nothing`() {
        val project = writes.createProject(CreateProject("All Finished"))
        (1..3).forEach { number ->
            writes.createItem(project.slug, CreateItem(type = "task", name = "Leaf $number"))
            writes.updateItem(project.slug, "leaf-$number", UpdateItem(status = FieldChange.Set("done")))
        }

        assertEquals(emptyList(), reads.getReadyItems(project.slug))
    }

    @Test
    fun `a deleted todo leaf is offered nowhere`() {
        val project = writes.createProject(CreateProject("Deleted Leaf"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Doomed leaf"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Surviving leaf"))

        writes.deleteItem(project.slug, "doomed-leaf")

        assertEquals(listOf("surviving-leaf"), reads.getReadyItems(project.slug).map { it.slug })
        assertEquals(
            listOf("surviving-leaf"),
            reads.listItems(project.slug, ItemFilter()).map { it.slug },
        )
    }

    @Test
    fun `ready leaves come back newest created first, carrying their blocker sets`() {
        val project = writes.createProject(CreateProject("Ready Order"))
        val resolved = writes.createItem(project.slug, CreateItem(type = "task", name = "Resolved blocker"))
        writes.updateItem(project.slug, "resolved-blocker", UpdateItem(status = FieldChange.Set("cancelled")))
        val ready = (1..3).map { number ->
            writes.createItem(project.slug, CreateItem(type = "task", name = "Ready $number")).id
        }
        writes.setItemBlockedBy(project.slug, "ready-2", SetItemBlockedBy(listOf("resolved-blocker")))

        val offered = reads.getReadyItems(project.slug)

        assertEquals(ready.reversed(), offered.map { it.id })
        assertEquals(setOf(resolved.id), offered.single { it.slug == "ready-2" }.blockedBy)
    }
}
