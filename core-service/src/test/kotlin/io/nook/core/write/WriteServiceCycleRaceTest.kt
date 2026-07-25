package io.nook.core.write

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.ErrorCode
import io.nook.contract.SetItemBlockedBy
import io.nook.contract.StructuredErrorException
import io.nook.core.db.EmbeddedPostgresSupport
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * The cycle race: two callers simultaneously write the two halves of what
 * would together be a two-edge dependency loop, 100 rounds. Every round must
 * end with exactly one success and one cycle rejection — the store never
 * holds a loop.
 */
class WriteServiceCycleRaceTest {

    @Test
    fun `of two simultaneous half-loop writers, exactly one succeeds and no loop is ever stored`() {
        val db = Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase())
        val service = WriteService(db)
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(100) { round ->
                val project = service.createProject(CreateProject("Cycle Round $round"))
                service.createItem(project.slug, CreateItem(type = "task", name = "x"))
                service.createItem(project.slug, CreateItem(type = "task", name = "y"))

                val barrier = CyclicBarrier(2)
                val outcomes = listOf("x" to "y", "y" to "x").map { (item, blocker) ->
                    pool.submit(
                        Callable {
                            barrier.await(10, TimeUnit.SECONDS)
                            runCatching {
                                service.setItemBlockedBy(project.slug, item, SetItemBlockedBy(listOf(blocker)))
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
