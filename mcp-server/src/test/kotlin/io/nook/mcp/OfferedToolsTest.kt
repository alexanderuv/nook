package io.nook.mcp

import io.modelcontextprotocol.spec.McpSchema
import io.nook.contract.aProject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What one tool takes: every argument it defines, and the ones a caller cannot leave out. */
private data class Offered(val arguments: Set<String>, val required: Set<String>)

/**
 * The whole surface an agent is offered, written out here rather than read from
 * the contract.
 *
 * The server derives every one of these from the payload shapes, so a check that
 * read the same shapes would agree with whatever they said, including a field
 * silently lost on the way. This list is the other side of that comparison: it
 * is what a person read and agreed to, and an operation that gains or loses an
 * argument fails here until someone has looked at it.
 */
private val THE_SEVEN = mapOf(
    "create_item" to Offered(
        setOf("type", "name", "slug", "description", "parentRef", "releaseRef"),
        setOf("type", "name"),
    ),
    "update_item" to Offered(
        setOf("ref", "name", "slug", "description", "status", "type", "parentRef", "releaseRef", "blockedBy"),
        setOf("ref"),
    ),
    "delete_item" to Offered(setOf("ref"), setOf("ref")),
    "create_release" to Offered(setOf("name", "slug", "description", "targetDate"), setOf("name")),
    "update_release" to Offered(setOf("ref", "name", "slug", "description", "status", "targetDate"), setOf("ref")),
    "get_item" to Offered(setOf("ref"), setOf("ref")),
    "list_items" to Offered(setOf("types", "statuses", "parents", "releases", "heldUp"), emptySet()),
)

@Suppress("UNCHECKED_CAST")
private fun McpSchema.Tool.arguments(): Map<String, Map<String, Any>> =
    inputSchema()["properties"] as Map<String, Map<String, Any>>

@Suppress("UNCHECKED_CAST")
private fun McpSchema.Tool.requires(): Set<String> = (inputSchema()["required"] as List<String>?).orEmpty().toSet()

/**
 * What an ordinary client is told it can call, read back through that client.
 *
 * This is the whole offer in one place: the names, what each takes, what each
 * says about itself, and — as much as it matters — everything the offer does
 * *not* hold.
 */
class OfferedToolsTest {

    private val project = aProject()

    private val core = StandInCore().apply { projects += project }

    private val door = FrontDoor(core)

    private val client = door.connectedAt(project.slug)

    private val offered = client.listTools().tools()

    @AfterTest
    fun letGo() {
        door.close()
    }

    @Test
    fun `the listing holds exactly the seven, under the names the contract gives them`() {
        assertEquals(THE_SEVEN.keys, offered.map { it.name() }.toSet())
        assertEquals(THE_SEVEN.size, offered.size, "a tool is offered twice")
    }

    @Test
    fun `each tool takes exactly its operation's arguments, with the project left out`() {
        offered.forEach { tool ->
            val expected = THE_SEVEN.getValue(tool.name())
            assertEquals(expected.arguments, tool.arguments().keys, "${tool.name()} takes the wrong arguments")
            assertEquals(expected.required, tool.requires(), "${tool.name()} requires the wrong arguments")
        }
    }

    @Test
    fun `every tool and every argument says what it is for`() {
        offered.forEach { tool ->
            assertTrue(tool.description().isNotBlank(), "${tool.name()} says nothing about what it does")
            tool.arguments().forEach { (name, argument) ->
                assertTrue(
                    (argument["description"] as String?)?.isNotBlank() == true,
                    "${tool.name()}'s $name argument carries no description",
                )
            }
        }
    }

    @Test
    fun `no tool names a project, and none accepts an argument it does not define`() {
        offered.forEach { tool ->
            // The names alone. A tool's description says which project it works
            // in, deliberately — that is the connection speaking, not an
            // argument an agent could set.
            assertFalse(tool.name().contains("project"), "${tool.name()} names a project")
            tool.arguments().keys.forEach {
                assertFalse(it.lowercase().contains("project"), "${tool.name()} takes an argument naming a project")
            }
            assertEquals(
                false,
                tool.inputSchema()["additionalProperties"],
                "${tool.name()} does not refuse the arguments it never defined",
            )
        }
    }

    @Test
    fun `nothing but a tool reaches a Nook operation`() {
        val offers = client.serverCapabilities

        assertNotNull(offers.tools(), "the server offers no tools")
        assertNull(offers.resources(), "the server offers resources")
        assertNull(offers.prompts(), "the server offers prompts")
        assertNull(offers.completions(), "the server offers completions")
        assertNull(offers.experimental(), "the server offers something outside the protocol")

        // Asked anyway — including for the protocol's own logging channel, which
        // the library adds and which nothing of Nook's is reachable through.
        assertTrue(runCatching { client.listResources().resources() }.getOrNull().isNullOrEmpty())
        assertTrue(runCatching { client.listPrompts().prompts() }.getOrNull().isNullOrEmpty())
        runCatching { client.setLoggingLevel(McpSchema.LoggingLevel.DEBUG) }

        assertEquals(emptyList(), core.toolCalls, "something other than a tool reached the core")
    }
}
