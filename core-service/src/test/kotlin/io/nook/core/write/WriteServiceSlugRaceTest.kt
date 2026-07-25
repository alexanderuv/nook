package io.nook.core.write

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
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
 * The slug race: two callers simultaneously create an identically named item
 * in the same project, 100 rounds. Every round must yield two successes with
 * distinct slugs — the store never holds two equal slugs in one project.
 */
class WriteServiceSlugRaceTest {

    @Test
    fun `two simultaneous creators of the same name always both succeed with distinct slugs`() {
        val db = Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase())
        val service = WriteService(db)
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(100) { round ->
                val project = service.createProject(CreateProject("Race Round $round"))
                val barrier = CyclicBarrier(2)
                val created = (1..2).map {
                    pool.submit(
                        Callable {
                            barrier.await(10, TimeUnit.SECONDS)
                            service.createItem(project.slug, CreateItem(type = "task", name = "Add search"))
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
