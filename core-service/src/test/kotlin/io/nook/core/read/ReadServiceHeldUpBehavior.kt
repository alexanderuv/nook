package io.nook.core.read

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.FieldChange
import io.nook.contract.ItemFilter
import io.nook.contract.ParentFilter
import io.nook.contract.UpdateItem
import io.nook.core.catalog.CatalogBehavior
import kotlin.test.Test
import kotlin.test.assertEquals

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
abstract class ReadServiceHeldUpBehavior : CatalogBehavior() {

    private companion object {
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
        val project = service.createProject(CreateProject("Ready Work"))
        service.createItem(project.slug, CreateItem(type = "epic", name = "An epic"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Finished blocker"))
        service.updateItem(project.slug, "finished-blocker", UpdateItem(status = FieldChange.Set("done")))
        service.createItem(project.slug, CreateItem(type = "task", name = "Deleted blocker"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Running blocker"))
        service.updateItem(project.slug, "running-blocker", UpdateItem(status = FieldChange.Set("in_progress")))

        service.createItem(project.slug, CreateItem(type = "task", name = "Free leaf"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Cleared leaf"))
        service.updateItem(project.slug, "cleared-leaf", blockedBy("finished-blocker", "deleted-blocker"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Held leaf"))
        service.updateItem(project.slug, "held-leaf", blockedBy("running-blocker"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Finished leaf"))
        service.updateItem(project.slug, "finished-leaf", UpdateItem(status = FieldChange.Set("done")))

        service.deleteItem(project.slug, "deleted-blocker")

        // Newest created first: the cleared leaf was created after the free one.
        // Absent, each for its own reason: the epic (the type part), the held leaf
        // (the held-up part), the finished leaf and the two surviving blockers
        // (the status part), and the deleted one, which is in no listing at all.
        assertEquals(
            listOf("cleared-leaf", "free-leaf"),
            service.listItems(project.slug, readyToWorkOn).map { it.slug },
        )
    }

    @Test
    fun `a project whose every leaf is done offers nothing`() {
        val project = service.createProject(CreateProject("All Finished"))
        (1..3).forEach { number ->
            service.createItem(project.slug, CreateItem(type = "task", name = "Leaf $number"))
            service.updateItem(project.slug, "leaf-$number", UpdateItem(status = FieldChange.Set("done")))
        }

        assertEquals(emptyList(), service.listItems(project.slug, readyToWorkOn))
    }

    @Test
    fun `adding the parent part asks the same question inside one epic`() {
        // The composition's payoff, and the reason readiness is not an operation:
        // an operation that took no filter could not be asked this at all.
        val project = service.createProject(CreateProject("Ready Here"))
        service.createItem(project.slug, CreateItem(type = "epic", name = "Epic A"))
        service.createItem(project.slug, CreateItem(type = "epic", name = "Epic B"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Under A", parentRef = "epic-a"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Under B", parentRef = "epic-b"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Loose leaf"))

        assertEquals(
            listOf("loose-leaf", "under-b", "under-a"),
            service.listItems(project.slug, readyToWorkOn).map { it.slug },
        )
        assertEquals(
            listOf("under-a"),
            service.listItems(
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
        val project = service.createProject(CreateProject("Any Item Held Up"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Running blocker"))
        service.updateItem(project.slug, "running-blocker", UpdateItem(status = FieldChange.Set("in_progress")))
        service.createItem(project.slug, CreateItem(type = "epic", name = "An epic"))
        service.createItem(project.slug, CreateItem(type = "bug", name = "Blocked done bug"))
        service.updateItem(project.slug, "blocked-done-bug", blockedBy("running-blocker"))
        service.updateItem(project.slug, "blocked-done-bug", UpdateItem(status = FieldChange.Set("done")))
        service.createItem(project.slug, CreateItem(type = "task", name = "Free leaf"))

        assertEquals(
            listOf("free-leaf", "an-epic", "running-blocker"),
            service.listItems(project.slug, ItemFilter(heldUp = false)).map { it.slug },
        )
        assertEquals(
            listOf("an-epic"),
            service.listItems(project.slug, ItemFilter(heldUp = false, types = listOf("epic"))).map { it.slug },
        )
        assertEquals(
            listOf("blocked-done-bug"),
            service.listItems(project.slug, ItemFilter(heldUp = true)).map { it.slug },
        )
        assertEquals(
            listOf("blocked-done-bug"),
            service.listItems(project.slug, ItemFilter(heldUp = true, statuses = listOf("done"))).map { it.slug },
        )
    }

    @Test
    fun `a deleted todo leaf is offered nowhere`() {
        val project = service.createProject(CreateProject("Deleted Leaf"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Doomed leaf"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Surviving leaf"))

        service.deleteItem(project.slug, "doomed-leaf")

        assertEquals(
            listOf("surviving-leaf"),
            service.listItems(project.slug, readyToWorkOn).map { it.slug },
        )
        assertEquals(
            listOf("surviving-leaf"),
            service.listItems(project.slug, ItemFilter()).map { it.slug },
        )
    }

    @Test
    fun `answers come back newest created first, carrying their blocker sets`() {
        val project = service.createProject(CreateProject("Ready Order"))
        val resolved = service.createItem(project.slug, CreateItem(type = "task", name = "Resolved blocker"))
        service.updateItem(project.slug, "resolved-blocker", UpdateItem(status = FieldChange.Set("cancelled")))
        val ready = (1..3).map { number ->
            service.createItem(project.slug, CreateItem(type = "task", name = "Ready $number")).id
        }
        service.updateItem(project.slug, "ready-2", blockedBy("resolved-blocker"))

        val offered = service.listItems(project.slug, readyToWorkOn)

        assertEquals(ready.reversed(), offered.map { it.id })
        assertEquals(setOf(resolved.id), offered.single { it.slug == "ready-2" }.blockedBy)
    }

    @Test
    fun `the part stops at the project it is asked in`() {
        val here = service.createProject(CreateProject("Held Up Here"))
        val elsewhere = service.createProject(CreateProject("Held Up Elsewhere"))
        service.createItem(elsewhere.slug, CreateItem(type = "task", name = "Their running blocker"))
        service.updateItem(
            elsewhere.slug, "their-running-blocker",
            UpdateItem(status = FieldChange.Set("in_progress")),
        )
        service.createItem(elsewhere.slug, CreateItem(type = "task", name = "Their held leaf"))
        service.updateItem(elsewhere.slug, "their-held-leaf", blockedBy("their-running-blocker"))
        service.createItem(here.slug, CreateItem(type = "task", name = "Our free leaf"))

        assertEquals(emptyList(), service.listItems(here.slug, ItemFilter(heldUp = true)).map { it.slug })
        assertEquals(listOf("our-free-leaf"), service.listItems(here.slug, ItemFilter(heldUp = false)).map { it.slug })
    }
}

class ReadServiceHeldUpInProcessTest : ReadServiceHeldUpBehavior() {
    override val reach = Reach.IN_PROCESS
}

class ReadServiceHeldUpAcrossConnectionTest : ReadServiceHeldUpBehavior() {
    override val reach = Reach.ACROSS_THE_CONNECTION
}
