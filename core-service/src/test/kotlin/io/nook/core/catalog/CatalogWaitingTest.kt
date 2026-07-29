package io.nook.core.catalog

import io.nook.contract.BreakdownException
import io.nook.contract.BreakdownOrigin
import io.nook.contract.CatalogClient
import io.nook.contract.CatalogOperation
import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.FieldChange
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateItem
import io.nook.core.db.EmbeddedPostgresSupport
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * What a caller is owed when the core is slow: a limit that fires where it says
 * it does, one send per call however that call ends, and no call waiting on an
 * unrelated one.
 *
 * The counts are taken on the receiving side, which is the only place that can
 * settle the resend question — a caller cannot tell how many times its request
 * arrived, and every one of these calls looks identical from where it was made.
 */
class CatalogWaitingTest {

    private companion object {
        val db by lazy { Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase()) }
        val inProcess by lazy { CoreCatalog(db).forActor(CatalogBehavior.SOMEBODY) }

        /** Well past any window a resend could hide in — sixteen times the limit below. */
        val LONG_ENOUGH_FOR_A_RESEND = 1_000L
    }

    @Test
    fun `a call the core holds without answering gives up between 30 and 31 seconds`() {
        val holding = CountDownLatch(1)
        val neverAnswering = InterceptingCatalog(inProcess) { _, _ -> holding.await(2, TimeUnit.MINUTES) }
        try {
            Connection(neverAnswering).use { connection ->
                val waited = measureTime {
                    val breakdown = assertFailsWith<BreakdownException> { connection.caller.listProjects() }
                    assertEquals(BreakdownOrigin.CONNECTION, breakdown.origin)
                }
                assertTrue(waited >= 30.seconds, "gave up after $waited, short of the limit")
                assertTrue(waited < 31.seconds, "waited $waited, past the limit")
                holding.countDown()
            }
        } finally {
            holding.countDown()
        }
    }

    @Test
    fun `the core receives a request exactly once, whichever way the call ends`() {
        val reached = AtomicInteger()
        val counting = InterceptingCatalog(inProcess) { _, _ -> reached.incrementAndGet() }

        val project = inProcess.createProject(CreateProject("Counted Once"))
        inProcess.createItem(project.slug, CreateItem(type = "task", name = "Add search"))
        inProcess.createItem(project.slug, CreateItem(type = "task", name = "Index docs"))
        inProcess.updateItem(
            project.slug,
            "index-docs",
            UpdateItem(blockedBy = FieldChange.Set(listOf("add-search"))),
        )

        Connection(counting).use { connection ->
            listOf<() -> Unit>(
                { connection.caller.createItem(project.slug, CreateItem(type = "story", name = "Not a thing")) },
                { connection.caller.getItem(project.slug, "no-such-item") },
                { connection.caller.createProject(CreateProject("Taken", slug = project.slug)) },
                {
                    connection.caller.updateItem(
                        project.slug,
                        "add-search",
                        UpdateItem(blockedBy = FieldChange.Set(listOf("index-docs"))),
                    )
                },
            ).forEach { refused ->
                reached.set(0)
                assertFailsWith<StructuredErrorException> { refused() }
                assertEquals(1, reached.get(), "a refused call reached the core more than once")
            }
        }

        val slowly = InterceptingCatalog(inProcess) { _, _ ->
            reached.incrementAndGet()
            Thread.sleep(400)
        }

        reached.set(0)
        Connection(slowly, waitLimit = 60.milliseconds).use { connection ->
            assertFailsWith<BreakdownException> { connection.caller.listProjects() }
            Thread.sleep(LONG_ENOUGH_FOR_A_RESEND)
            assertEquals(1, reached.get(), "a call that ran past the limit was sent again")
        }

        reached.set(0)
        Connection(slowly).use { connection ->
            val inFlight = thread { runCatching { connection.caller.listProjects() } }
            Thread.sleep(100)
            connection.dropTheConnection()
            inFlight.join()
            Thread.sleep(LONG_ENOUGH_FOR_A_RESEND)
            assertEquals(1, reached.get(), "a call whose connection dropped was sent again")
        }
    }

    @Test
    fun `a fast call made by another caller while a slow one is in flight is answered first`() {
        val project = inProcess.createProject(CreateProject("Slow Beside Fast"))
        val slowly = InterceptingCatalog(inProcess) { operation, _ ->
            if (operation == CatalogOperation.GET_PROJECT) Thread.sleep(3_000)
        }

        Connection(slowly).use { connection ->
            CatalogClient(connection.address).use { second ->
                val answered = ConcurrentLinkedQueue<String>()
                val slow = thread {
                    connection.caller.getProject(project.slug)
                    answered += "slow"
                }
                Thread.sleep(300)
                second.listProjects()
                answered += "fast"
                slow.join()

                assertEquals(listOf("fast", "slow"), answered.toList())
            }
        }
    }
}
