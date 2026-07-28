package io.nook.mcp

import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import io.nook.contract.DEFAULT_WAIT_LIMIT
import io.nook.contract.ProjectItem
import io.nook.contract.catalogJson
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.serialization.builtins.ListSerializer

/** The one call held open on purpose, named so the stand-in can tell it from the others. */
private const val THE_SLOW_ONE = "the-slow-one"

/**
 * Long enough that no loaded machine trips it, short enough that a regression
 * fails the test rather than wedging the run.
 */
private const val WAIT_SECONDS = 30L

/** What two round trips to a server on this machine take, with room to spare, and nothing like [WAIT_SECONDS]. */
private val PROMPTLY: Duration = 10.seconds

/**
 * Several agents at once, and one answer larger than any of the others.
 *
 * Every wait here is bounded and every worker is a daemon. A test about one
 * call waiting on another is exactly the one that hangs when the thing it tests
 * breaks, and a hang is the worst failure to receive: no assertion message, no
 * report, and a build that runs until something outside it gives up.
 */
class ManyAtOnceTest {

    private val searchRevamp = aProject()

    private val billing = aProject(
        name = "Billing",
        slug = "billing",
        description = "Invoices and dunning.",
        id = Uuid.parse("bbbbbbbb-0000-0000-0000-000000000002"),
    )

    private val core = StandInCore().apply {
        projects += searchRevamp
        projects += billing
    }

    private fun CountDownLatch.awaitOrFail(what: String) {
        if (!await(WAIT_SECONDS, TimeUnit.SECONDS)) throw AssertionError("timed out after ${WAIT_SECONDS}s for $what")
    }

    private fun Thread.joinOrFail(what: String) {
        join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        if (isAlive) throw AssertionError("$what never finished within ${WAIT_SECONDS}s")
    }

    private fun McpSyncClient.getItem(ref: String): McpSchema.CallToolResult =
        callTool(McpSchema.CallToolRequest.builder("get_item").arguments(mapOf("ref" to ref)).build())

    @Test
    fun `three connections call at once, and neither fast call waits on the slow one`() {
        val slowArrived = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val slowFinished = AtomicBoolean(false)
        core.answering = { asked ->
            if (asked.values.firstOrNull() == THE_SLOW_ONE) {
                slowArrived.countDown()
                releaseSlow.awaitOrFail("the test to release the slow call")
            }
            anItem()
        }

        FrontDoor(core).use { door ->
            val onSearchByHandle = door.connectedAt(searchRevamp.slug)
            val onSearchById = door.connectedAt(searchRevamp.id.toString())
            val onBilling = door.connectedAt(billing.slug)

            val slow = thread(isDaemon = true) {
                onSearchByHandle.getItem(THE_SLOW_ONE)
                slowFinished.set(true)
            }
            slowArrived.awaitOrFail("the slow call to reach the core")

            val started = System.nanoTime()
            assertEquals(false, onSearchById.getItem("quick-one").isError())
            assertEquals(false, onBilling.getItem("quick-two").isError())
            val waited = (System.nanoTime() - started).nanoseconds

            assertFalse(slowFinished.get(), "the fast calls only came back once the slow one had finished")
            // Both readings are needed. Served one at a time, the fast calls
            // come back the moment the slow one gives up waiting — which it
            // does, loudly, but only after long enough that "it finished
            // first" would be true and say nothing.
            assertTrue(waited < PROMPTLY, "the fast calls waited $waited on a call held open on another connection")

            releaseSlow.countDown()
            slow.joinOrFail("the slow call")

            val insideItsOwnProject: Map<Any?, String?> = mapOf(
                THE_SLOW_ONE to searchRevamp.id.toString(),
                "quick-one" to searchRevamp.id.toString(),
                "quick-two" to billing.id.toString(),
            )
            assertEquals(
                insideItsOwnProject,
                core.toolCalls.associate { it.values.single() to it.project },
                "a call acted inside a project that was not its own connection's",
            )
        }
    }

    @Test
    fun `a listing of five thousand items arrives whole, in order, inside the wait limit`() {
        val many = (1..5_000).map {
            anItem(name = "Item $it", slug = "item-$it", id = Uuid.parse("cccccccc-0000-0000-0000-%012d".format(it)))
        }
        core.answering = { many }

        FrontDoor(core).use { door ->
            val client = door.connectedAt(searchRevamp.slug)

            val started = System.nanoTime()
            val answered = client.callTool(McpSchema.CallToolRequest.builder("list_items").arguments(emptyMap()).build())
            val took = (System.nanoTime() - started).nanoseconds

            assertEquals(false, answered.isError(), "the listing was not served; it said: ${answered.said()}")
            assertEquals(
                many,
                catalogJson.decodeFromString(ListSerializer(ProjectItem.serializer()), answered.said()),
                "the listing did not arrive whole and in the order the core put it in",
            )
            assertTrue(took < DEFAULT_WAIT_LIMIT, "a listing of ${many.size} items took $took")
        }
    }

    @Test
    fun `a tool call sent before the opening handshake is not served`() {
        FrontDoor(core).use { door ->
            val answered = door.callingWithoutOpeningAt("$TOOLS_PATH/${searchRevamp.slug}")

            assertTrue(
                answered.status != 200,
                "a call made before the handshake was served; it answered ${answered.status}: ${answered.said}",
            )
            assertEquals(emptyList(), core.toolCalls, "a call made before the handshake reached the core")
        }
    }
}
