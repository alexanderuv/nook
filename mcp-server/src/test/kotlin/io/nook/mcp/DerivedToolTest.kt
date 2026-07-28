package io.nook.mcp

import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import io.nook.contract.CatalogOperation
import io.nook.contract.FieldChange
import io.nook.contract.UpdateItem
import io.nook.contract.aProject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A tool built from nothing but what its payload shape says about itself,
 * driven through the protocol library's own client.
 *
 * `update_item` is the one driven here because it is the hardest: its
 * conversion is written by hand, and the distinction it exists to keep — a
 * field nobody mentioned against a field set to nothing — is the one a
 * declaration derived mechanically is most likely to lose. If a declaration
 * derived this way carries that distinction, the six easier ones follow.
 */
class DerivedToolTest {

    private val project = aProject()

    private val core = StandInCore().apply { projects += project }

    /** Opens one connection to [project], runs [check], and lets go of the whole door. */
    private fun connected(check: (McpSyncClient) -> Unit) {
        FrontDoor(core).use { door -> check(door.connectedAt(project.slug)) }
    }

    /** The changes the stand-in was asked to make, from the one call it recorded. */
    private fun theUpdate(): UpdateItem {
        val update = core.invocationsOf(CatalogOperation.UPDATE_ITEM).single()
        return update.values[1] as UpdateItem
    }

    private fun McpSyncClient.update(vararg arguments: Pair<String, Any?>): McpSchema.CallToolResult =
        callTool(McpSchema.CallToolRequest.builder("update_item").arguments(mapOf(*arguments)).build())

    @Test
    fun `an ordinary client completes the handshake and is told what the tool takes`() {
        connected { client ->
            val update = client.listTools().tools().single { it.name() == "update_item" }

            assertTrue(update.description().isNotBlank(), "update_item says nothing about what it does")

            @Suppress("UNCHECKED_CAST")
            val arguments = update.inputSchema()["properties"] as Map<String, Map<String, Any>>
            assertEquals(
                setOf("ref", "name", "slug", "description", "status", "type", "parentRef", "releaseRef", "blockedBy"),
                arguments.keys,
                "the tool's arguments are not the ones its payload declares",
            )
            arguments.forEach { (name, argument) ->
                assertTrue(
                    (argument["description"] as String?)?.isNotBlank() == true,
                    "the $name argument carries no description",
                )
            }

            assertEquals(listOf("ref"), update.inputSchema()["required"])
            assertEquals(false, update.inputSchema()["additionalProperties"], "the tool accepts undeclared arguments")
            // A field that may be cleared is declared as one accepting nothing,
            // which is the whole of what makes clearing it expressible.
            assertEquals(listOf("string", "null"), arguments.getValue("description")["type"])
            assertEquals("string", arguments.getValue("name")["type"])
            assertEquals("array", arguments.getValue("blockedBy")["type"])
        }
    }

    @Test
    fun `a field left unmentioned, a field set, and a field cleared arrive as three different things`() {
        connected { client ->
            client.update("ref" to "add-search", "name" to "Renamed")
        }
        assertEquals(UpdateItem(name = FieldChange.Set("Renamed")), theUpdate())

        core.invocations.clear()
        connected { client ->
            client.update("ref" to "add-search", "description" to null)
        }
        assertEquals(UpdateItem(description = FieldChange.Set(null)), theUpdate())

        core.invocations.clear()
        connected { client ->
            client.update("ref" to "add-search", "description" to "Something new")
        }
        assertEquals(UpdateItem(description = FieldChange.Set("Something new")), theUpdate())

        core.invocations.clear()
        connected { client ->
            client.update("ref" to "add-search")
        }
        assertEquals(UpdateItem(), theUpdate())
    }

    @Test
    fun `arguments that do not fit the tool are turned back naming the one at fault`() {
        val notFitting = listOf(
            "colour" to arrayOf("ref" to "add-search", "colour" to "green"),
            "ref" to arrayOf("name" to "Renamed"),
            "name" to arrayOf<Pair<String, Any?>>("ref" to "add-search", "name" to 42),
            "blockedBy" to arrayOf<Pair<String, Any?>>("ref" to "add-search", "blockedBy" to "just the one"),
        )

        connected { client ->
            notFitting.forEach { (atFault, arguments) ->
                val answered = client.update(*arguments)

                assertEquals(true, answered.isError(), "arguments the tool cannot fit were accepted: $atFault")
                assertTrue(
                    answered.said().contains(atFault),
                    "the refusal does not name the argument at fault; it said: ${answered.said()}",
                )
            }
            // And the connection is none the worse for any of them.
            assertEquals(false, client.update("ref" to "add-search").isError())
        }
        assertEquals(
            listOf(UpdateItem()),
            core.toolCalls.map { it.values[1] },
            "a call the tool could not read reached the core",
        )
    }

    @Test
    fun `a call the tool can read reaches the core, and its answer comes back whole`() {
        connected { client ->
            val answered = client.update("ref" to "add-search", "status" to "done")

            assertEquals(false, answered.isError())
            assertNotNull(answered.structuredContent(), "an answer carried no structured form of the entity")
            val said = answered.content().filterIsInstance<McpSchema.TextContent>().single().text()
            assertTrue(said.contains("\"slug\":\"add-search\""), "the answer did not carry the entity; it said: $said")
        }
        assertEquals(UpdateItem(status = FieldChange.Set("done")), theUpdate())
    }
}
