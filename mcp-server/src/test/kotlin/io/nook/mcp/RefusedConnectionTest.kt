package io.nook.mcp

import io.modelcontextprotocol.spec.McpSchema
import io.nook.contract.aProject
import io.nook.contract.unreachableCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** What an ordinary client makes of a connection it could not open. */
private const val UNAVAILABLE = 404

private const val CORE_UNREACHABLE = 503

/** What a request carrying a page's origin is answered with — the protocol library's own refusal. */
private const val FROM_A_PAGE = 403

/** Enough of a tool call to be served, so that not being served means the connection and nothing else. */
private val NAMING_ONE = mapOf("ref" to "add-search")

/**
 * The connections that are not served, and what an agent is handed instead.
 *
 * Each one is read twice: as the words the server wrote, which is what a person
 * debugging their configuration sees, and as what an ordinary client makes of
 * them, which is whether the whole server reads as unavailable rather than as
 * one that works until you call it.
 */
class RefusedConnectionTest {

    private val core = StandInCore().apply { projects += aProject() }

    /**
     * An ordinary client aimed at [projectRef], expected to be turned away twice
     * over: its connection never opens, and a tool call attempted anyway is not
     * served.
     */
    private fun RunningAdapter.turnedAwayAt(projectRef: String) {
        val client = clientAt(projectRef)
        runCatching { client.initialize() }
            .onSuccess { fail("a connection at '$projectRef' was served") }
        runCatching { client.callTool(McpSchema.CallToolRequest.builder("get_item").arguments(NAMING_ONE).build()) }
            .onSuccess { fail("a call on a refused connection at '$projectRef' was served") }
    }

    @Test
    fun `a mistyped project is refused in the opening exchange, naming what was asked for`() {
        RunningAdapter(core).use { adapter ->
            val answered = adapter.openingAt("$TOOLS_PATH/serch-revamp")

            assertEquals(UNAVAILABLE, answered.status)
            assertTrue(
                answered.said.contains("serch-revamp"),
                "the refusal does not name the project asked for; it said: ${answered.said}",
            )
            adapter.turnedAwayAt("serch-revamp")
            assertEquals(emptyList(), core.toolCalls, "a refused connection reached the core")
        }
    }

    @Test
    fun `an address naming no project at all is refused the same way`() {
        RunningAdapter(core).use { adapter ->
            val trailing = adapter.openingAt("$TOOLS_PATH/")
            val bare = adapter.openingAt(TOOLS_PATH)

            listOf(trailing, bare).forEach {
                assertEquals(UNAVAILABLE, it.status)
                assertTrue(it.said.contains("no project"), "the refusal says nothing about a project: ${it.said}")
            }
            adapter.turnedAwayAt("")
            assertEquals(emptyList(), core.toolCalls, "a refused connection reached the core")
        }
    }

    @Test
    fun `an address carrying more than a project reference is refused, naming the whole of it`() {
        RunningAdapter(core).use { adapter ->
            // Everything after the mount is the reference, rather than the first
            // piece of it: no reference holds a slash, so an address with more
            // in it is one nobody wrote — and reading only the first piece would
            // serve it as though they had.
            val answered = adapter.openingAt("$TOOLS_PATH/search-revamp/and-then-some")

            assertEquals(UNAVAILABLE, answered.status, "an address nobody wrote was served as one that was")
            assertTrue(
                answered.said.contains("search-revamp/and-then-some"),
                "the refusal does not repeat what was asked for; it said: ${answered.said}",
            )
        }
    }

    @Test
    fun `a configuration placeholder nobody filled in comes back as the person wrote it`() {
        RunningAdapter(core).use { adapter ->
            val answered = adapter.openingAt("$TOOLS_PATH/%7B%7BPROJECT_SLUG%7D%7D")

            assertEquals(UNAVAILABLE, answered.status)
            assertTrue(
                answered.said.contains("{{PROJECT_SLUG}}"),
                "the placeholder did not come back as it was written; it said: ${answered.said}",
            )
        }
    }

    @Test
    fun `a core that cannot be reached says so, and never that the project is missing`() {
        core.resolving = { unreachableCore() }
        RunningAdapter(core).use { adapter ->
            val answered = adapter.openingAt("$TOOLS_PATH/search-revamp")

            assertEquals(CORE_UNREACHABLE, answered.status)
            assertTrue(
                answered.said.contains("core could not be reached"),
                "the refusal does not say the core could not be reached; it said: ${answered.said}",
            )
            assertFalse(
                answered.said.contains("search-revamp"),
                "an unreachable core was reported as a project that does not exist: ${answered.said}",
            )
            adapter.turnedAwayAt("search-revamp")
            assertEquals(emptyList(), core.toolCalls, "a refused connection reached the core")
        }
    }

    @Test
    fun `an opening exchange a web page sent is turned away`() {
        RunningAdapter(core).use { adapter ->
            // A page open in a browser on this machine reaches the loopback
            // address as readily as an agent does, and this server asks for no
            // credential — so the binding alone would not keep it out.
            val fromAPage = adapter.openingAt("$TOOLS_PATH/search-revamp", origin = "http://a.page.example")

            assertEquals(FROM_A_PAGE, fromAPage.status, "a page in a browser was served; it said: ${fromAPage.said}")
            assertEquals(emptyList(), core.toolCalls, "a call a page made reached the core")
        }
    }

    @Test
    fun `the same adapter serves the project that does exist`() {
        RunningAdapter(core).use { adapter ->
            val opened = adapter.openingAt("$TOOLS_PATH/search-revamp")

            assertEquals(200, opened.status, "a good address was refused; it said: ${opened.said}")
        }
    }
}
