package io.nook.core.catalog

import io.nook.contract.BreakdownException
import io.nook.contract.BreakdownOrigin
import io.nook.contract.CatalogClient
import io.nook.contract.CatalogOperation
import io.nook.core.db.EmbeddedPostgresSupport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * One caller, never rebuilt, through a core that is absent, then there, then
 * taken away in the middle of a call, then back.
 *
 * The caller is built before anything is listening on purpose: an adapter
 * started before the core is the ordinary case, and what it must not do is need
 * restarting once the core arrives.
 */
class CatalogReachabilityTest {

    private companion object {
        val db by lazy { Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase()) }
    }

    private fun brokenLink(call: () -> Unit): BreakdownException {
        val breakdown = assertIs<BreakdownException>(runCatching(call).exceptionOrNull())
        assertEquals(BreakdownOrigin.CONNECTION, breakdown.origin)
        return breakdown
    }

    @Test
    fun `a caller outlives the core being absent, arriving, leaving mid-call, and coming back`() {
        val port = freePort()
        val address = "http://$LOOPBACK:$port"

        CatalogClient(address).use { caller ->
            brokenLink { caller.listProjects() }

            CatalogServer(CoreCatalog(db), LOOPBACK, port).use { core ->
                core.start()
                caller.listProjects()
            }

            brokenLink { caller.listProjects() }

            // Taken away mid-call: the core is holding a call when it stops, so
            // the caller learns the link came apart rather than getting an
            // answer or a refusal.
            val holding = CountDownLatch(1)
            val reached = CountDownLatch(1)
            val outcome = AtomicReference<Throwable?>()
            val heldCore = CatalogServer(
                InterceptingCatalog(CoreCatalog(db)) { operation, _ ->
                    if (operation == CatalogOperation.LIST_PROJECTS) {
                        reached.countDown()
                        holding.await(1, TimeUnit.MINUTES)
                    }
                },
                LOOPBACK,
                port,
            )
            heldCore.start()
            val inFlight = thread { outcome.set(runCatching { caller.listProjects() }.exceptionOrNull()) }
            check(reached.await(1, TimeUnit.MINUTES)) { "the held call never reached the core" }
            heldCore.close()
            holding.countDown()
            inFlight.join()

            val brokenOffMidCall = assertIs<BreakdownException>(assertNotNull(outcome.get()))
            assertEquals(BreakdownOrigin.CONNECTION, brokenOffMidCall.origin)

            CatalogServer(CoreCatalog(db), LOOPBACK, port).use { core ->
                core.start()
                caller.listProjects()
            }
        }
    }
}
