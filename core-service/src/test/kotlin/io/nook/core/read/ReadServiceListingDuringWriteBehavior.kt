package io.nook.core.read

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.FieldChange
import io.nook.contract.ItemFilter
import io.nook.contract.UpdateItem
import io.nook.core.catalog.CatalogBehavior
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One caller listing a project while another fills it, a hundred rounds of
 * each: every listing has to show the store either wholly before or wholly
 * after a write, never half-applied.
 *
 * It sits apart from the two checks on the read path's transaction discipline,
 * which drive that transaction directly and can only be asked in the core's own
 * process. This one asks about the operations, so it is asked both ways — and
 * across the connection the two callers really are two, each with its own
 * connection to the one core.
 */
abstract class ReadServiceListingDuringWriteBehavior : CatalogBehavior() {

    private companion object {
        const val ROUNDS = 100
    }

    @Test
    fun `a listing taken while another caller writes always shows fully committed items`() {
        val project = service.createProject(CreateProject("Listed While Written"))

        val started = CountDownLatch(1)
        val failures = mutableListOf<Throwable>()
        val writer = Thread {
            started.countDown()
            repeat(ROUNDS) { round ->
                runCatching {
                    otherService.createItem(project.slug, CreateItem(type = "task", name = "Blocked $round"))
                    otherService.createItem(project.slug, CreateItem(type = "task", name = "Blocker $round"))
                    otherService.updateItem(
                        project.slug, "blocked-$round",
                        UpdateItem(blockedBy = FieldChange.Set(listOf("blocker-$round"))),
                    )
                }.onFailure { synchronized(failures) { failures += it } }
            }
        }

        writer.start()
        started.await()
        val listings = buildList {
            repeat(ROUNDS) {
                add(runCatching { service.listItems(project.slug, ItemFilter()) })
            }
        }
        writer.join()

        assertEquals(emptyList(), failures.toList(), "no write may fail")
        listings.forEachIndexed { attempt, listing ->
            val items = listing.getOrElse { throw AssertionError("listing $attempt failed", it) }
            val visible = items.map { it.id }.toSet()
            items.forEach { item ->
                val unseen = item.blockedBy - visible
                assertTrue(
                    unseen.isEmpty(),
                    "listing $attempt gave ${item.slug} a blocker it never showed: $unseen",
                )
                val expected = "blocker-${item.slug.substringAfter('-')}"
                assertTrue(
                    item.blockedBy.size <= 1 &&
                        item.blockedBy.all { blockerId -> items.single { it.id == blockerId }.slug == expected },
                    "listing $attempt gave ${item.slug} a blocker set it was never written with",
                )
            }
        }
        assertEquals(2 * ROUNDS, service.listItems(project.slug, ItemFilter()).size)
    }
}

class ReadServiceListingDuringWriteInProcessTest : ReadServiceListingDuringWriteBehavior() {
    override val reach = Reach.IN_PROCESS
}

class ReadServiceListingDuringWriteAcrossConnectionTest : ReadServiceListingDuringWriteBehavior() {
    override val reach = Reach.ACROSS_THE_CONNECTION
}
