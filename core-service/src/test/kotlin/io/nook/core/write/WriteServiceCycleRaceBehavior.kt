package io.nook.core.write

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.ErrorCode
import io.nook.contract.FieldChange
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateItem
import io.nook.core.catalog.CatalogBehavior
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cycle race: two callers simultaneously write the two halves of what
 * would together be a two-edge dependency loop, 100 rounds. Every round must
 * end with exactly one success and one cycle rejection — the store never
 * holds a loop.
 */
abstract class WriteServiceCycleRaceBehavior : CatalogBehavior() {

    @Test
    fun `of two simultaneous half-loop writers, exactly one succeeds and no loop is ever stored`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(100) { round ->
                val project = service.createProject(CreateProject("Cycle Round $round"))
                service.createItem(project.slug, CreateItem(type = "task", name = "x"))
                service.createItem(project.slug, CreateItem(type = "task", name = "y"))

                val barrier = CyclicBarrier(2)
                val halves = listOf("x" to "y", "y" to "x") zip listOf(service, otherService)
                val outcomes = halves.map { (half, caller) ->
                    val (item, blocker) = half
                    pool.submit(
                        Callable {
                            barrier.await(10, TimeUnit.SECONDS)
                            runCatching {
                                caller.updateItem(
                                    project.slug, item,
                                    UpdateItem(blockedBy = FieldChange.Set(listOf(blocker))),
                                )
                            }
                        },
                    )
                }.map { it.get(30, TimeUnit.SECONDS) }

                val successes = outcomes.count { it.isSuccess }
                assertEquals(1, successes, "round $round: expected exactly one winner, got $successes")

                val failure = outcomes.first { it.isFailure }.exceptionOrNull()
                assertTrue(
                    failure is StructuredErrorException && failure.error.code == ErrorCode.CYCLE,
                    "round $round: the loser must fail with the cycle error, got $failure",
                )

                val xBlockers = readItem(db, project.slug, "x").blockedBy
                val yBlockers = readItem(db, project.slug, "y").blockedBy
                assertTrue(
                    xBlockers.isEmpty() != yBlockers.isEmpty(),
                    "round $round: the store holds a loop or lost both edges (x=$xBlockers, y=$yBlockers)",
                )
            }
        } finally {
            pool.shutdownNow()
        }
    }
}

class WriteServiceCycleRaceInProcessTest : WriteServiceCycleRaceBehavior() {
    override val reach = Reach.IN_PROCESS
}

class WriteServiceCycleRaceAcrossConnectionTest : WriteServiceCycleRaceBehavior() {
    override val reach = Reach.ACROSS_THE_CONNECTION
}
