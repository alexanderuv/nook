package io.nook.core.write

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.core.catalog.CatalogBehavior
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The slug race: two callers simultaneously create an identically named item
 * in the same project, 100 rounds. Every round must yield two successes with
 * distinct slugs — the store never holds two equal slugs in one project.
 */
abstract class WriteServiceSlugRaceBehavior : CatalogBehavior() {

    @Test
    fun `two simultaneous creators of the same name always both succeed with distinct slugs`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(100) { round ->
                val project = service.createProject(CreateProject("Race Round $round"))
                val barrier = CyclicBarrier(2)
                val created = listOf(service, otherService).map { caller ->
                    pool.submit(
                        Callable {
                            barrier.await(10, TimeUnit.SECONDS)
                            caller.createItem(project.slug, CreateItem(type = "task", name = "Add search"))
                        },
                    )
                }.map { it.get(30, TimeUnit.SECONDS) }

                val slugs = created.map { it.slug }.toSet()
                assertEquals(2, slugs.size, "round $round: both creators got the same slug $slugs")
                assertTrue("add-search" in slugs, "round $round: no creator got the unsuffixed slug ($slugs)")
            }
        } finally {
            pool.shutdownNow()
        }
    }
}

class WriteServiceSlugRaceInProcessTest : WriteServiceSlugRaceBehavior() {
    override val reach = Reach.IN_PROCESS
}

class WriteServiceSlugRaceAcrossConnectionTest : WriteServiceSlugRaceBehavior() {
    override val reach = Reach.ACROSS_THE_CONNECTION
}
