package io.nook.core.read

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.FieldChange
import io.nook.contract.ItemFilter
import io.nook.contract.ParentFilter
import io.nook.contract.UpdateItem
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.write.WriteService
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * The filter part that asks whether anything unfinished is holding an item up,
 * and the question the whole milestone is judged on, which is that part combined
 * with two ordinary ones: the leaf types, status `todo`, and not held up.
 *
 * A blocker stops holding anything up when it is done, cancelled, or deleted —
 * gone work must not deadlock what sits behind it. The part itself says nothing
 * about type or status, which is what lets it narrow alongside the others
 * instead of quietly deciding for them.
 */
class ReadServiceHeldUpTest {

    private companion object {
        val db by lazy { Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase()) }
        val writes by lazy { WriteService(db) }
        val reads by lazy { ReadService(db) }

        /** The composed question: what can be worked on. */
        val readyToWorkOn = ItemFilter(
            types = listOf("task", "bug", "chore"),
            statuses = listOf("todo"),
            heldUp = false,
        )
    }

    private fun blockedBy(vararg refs: String) = UpdateItem(blockedBy = FieldChange.Set(refs.toList()))

    @Test
    fun `the composed question returns exactly the unblocked todo leaves, and nothing else`() {
        val project = writes.createProject(CreateProject("Ready Work"))
        writes.createItem(project.slug, CreateItem(type = "epic", name = "An epic"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Finished blocker"))
        writes.updateItem(project.slug, "finished-blocker", UpdateItem(status = FieldChange.Set("done")))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Deleted blocker"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Running blocker"))
        writes.updateItem(project.slug, "running-blocker", UpdateItem(status = FieldChange.Set("in_progress")))

        writes.createItem(project.slug, CreateItem(type = "task", name = "Free leaf"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Cleared leaf"))
        writes.updateItem(project.slug, "cleared-leaf", blockedBy("finished-blocker", "deleted-blocker"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Held leaf"))
        writes.updateItem(project.slug, "held-leaf", blockedBy("running-blocker"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Finished leaf"))
        writes.updateItem(project.slug, "finished-leaf", UpdateItem(status = FieldChange.Set("done")))

        writes.deleteItem(project.slug, "deleted-blocker")

        // Newest created first: the cleared leaf was created after the free one.
        // Absent, each for its own reason: the epic (the type part), the held leaf
        // (the held-up part), the finished leaf and the two surviving blockers
        // (the status part), and the deleted one, which is in no listing at all.
        assertEquals(
            listOf("cleared-leaf", "free-leaf"),
            reads.listItems(project.slug, readyToWorkOn).map { it.slug },
        )
    }

    @Test
    fun `a project whose every leaf is done offers nothing`() {
        val project = writes.createProject(CreateProject("All Finished"))
        (1..3).forEach { number ->
            writes.createItem(project.slug, CreateItem(type = "task", name = "Leaf $number"))
            writes.updateItem(project.slug, "leaf-$number", UpdateItem(status = FieldChange.Set("done")))
        }

        assertEquals(emptyList(), reads.listItems(project.slug, readyToWorkOn))
    }

    @Test
    fun `adding the parent part asks the same question inside one epic`() {
        // The composition's payoff, and the reason readiness is not an operation:
        // an operation that took no filter could not be asked this at all.
        val project = writes.createProject(CreateProject("Ready Here"))
        writes.createItem(project.slug, CreateItem(type = "epic", name = "Epic A"))
        writes.createItem(project.slug, CreateItem(type = "epic", name = "Epic B"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Under A", parentRef = "epic-a"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Under B", parentRef = "epic-b"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Loose leaf"))

        assertEquals(
            listOf("loose-leaf", "under-b", "under-a"),
            reads.listItems(project.slug, readyToWorkOn).map { it.slug },
        )
        assertEquals(
            listOf("under-a"),
            reads.listItems(
                project.slug,
                readyToWorkOn.copy(parents = listOf(ParentFilter.Epic("epic-a"))),
            ).map { it.slug },
        )
    }

    @Test
    fun `the part implies no type and no status of its own`() {
        // The view this part replaced answered "ready", which meant leaf AND todo
        // AND unblocked all at once. Those two extra clauses are what must not
        // have come along: an epic is not held up rather than not answered, and a
        // done item with an unfinished blocker is held up like any other.
        //
        // A held-up epic is unreachable on purpose and so goes untested: an epic
        // takes no blockers, and a leaf holding edges cannot be promoted into one.
        val project = writes.createProject(CreateProject("Any Item Held Up"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Running blocker"))
        writes.updateItem(project.slug, "running-blocker", UpdateItem(status = FieldChange.Set("in_progress")))
        writes.createItem(project.slug, CreateItem(type = "epic", name = "An epic"))
        writes.createItem(project.slug, CreateItem(type = "bug", name = "Blocked done bug"))
        writes.updateItem(project.slug, "blocked-done-bug", blockedBy("running-blocker"))
        writes.updateItem(project.slug, "blocked-done-bug", UpdateItem(status = FieldChange.Set("done")))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Free leaf"))

        assertEquals(
            listOf("free-leaf", "an-epic", "running-blocker"),
            reads.listItems(project.slug, ItemFilter(heldUp = false)).map { it.slug },
        )
        assertEquals(
            listOf("an-epic"),
            reads.listItems(project.slug, ItemFilter(heldUp = false, types = listOf("epic"))).map { it.slug },
        )
        assertEquals(
            listOf("blocked-done-bug"),
            reads.listItems(project.slug, ItemFilter(heldUp = true)).map { it.slug },
        )
        assertEquals(
            listOf("blocked-done-bug"),
            reads.listItems(project.slug, ItemFilter(heldUp = true, statuses = listOf("done"))).map { it.slug },
        )
    }

    @Test
    fun `a deleted todo leaf is offered nowhere`() {
        val project = writes.createProject(CreateProject("Deleted Leaf"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Doomed leaf"))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Surviving leaf"))

        writes.deleteItem(project.slug, "doomed-leaf")

        assertEquals(
            listOf("surviving-leaf"),
            reads.listItems(project.slug, readyToWorkOn).map { it.slug },
        )
        assertEquals(
            listOf("surviving-leaf"),
            reads.listItems(project.slug, ItemFilter()).map { it.slug },
        )
    }

    @Test
    fun `answers come back newest created first, carrying their blocker sets`() {
        val project = writes.createProject(CreateProject("Ready Order"))
        val resolved = writes.createItem(project.slug, CreateItem(type = "task", name = "Resolved blocker"))
        writes.updateItem(project.slug, "resolved-blocker", UpdateItem(status = FieldChange.Set("cancelled")))
        val ready = (1..3).map { number ->
            writes.createItem(project.slug, CreateItem(type = "task", name = "Ready $number")).id
        }
        writes.updateItem(project.slug, "ready-2", blockedBy("resolved-blocker"))

        val offered = reads.listItems(project.slug, readyToWorkOn)

        assertEquals(ready.reversed(), offered.map { it.id })
        assertEquals(setOf(resolved.id), offered.single { it.slug == "ready-2" }.blockedBy)
    }

    @Test
    fun `the part stops at the project it is asked in`() {
        val here = writes.createProject(CreateProject("Held Up Here"))
        val elsewhere = writes.createProject(CreateProject("Held Up Elsewhere"))
        writes.createItem(elsewhere.slug, CreateItem(type = "task", name = "Their running blocker"))
        writes.updateItem(
            elsewhere.slug, "their-running-blocker",
            UpdateItem(status = FieldChange.Set("in_progress")),
        )
        writes.createItem(elsewhere.slug, CreateItem(type = "task", name = "Their held leaf"))
        writes.updateItem(elsewhere.slug, "their-held-leaf", blockedBy("their-running-blocker"))
        writes.createItem(here.slug, CreateItem(type = "task", name = "Our free leaf"))

        assertEquals(emptyList(), reads.listItems(here.slug, ItemFilter(heldUp = true)).map { it.slug })
        assertEquals(listOf("our-free-leaf"), reads.listItems(here.slug, ItemFilter(heldUp = false)).map { it.slug })
    }
}
