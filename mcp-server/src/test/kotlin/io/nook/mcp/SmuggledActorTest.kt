package io.nook.mcp

import io.modelcontextprotocol.spec.McpSchema
import io.nook.contract.ALEX
import io.nook.contract.AN_AGENT
import io.nook.contract.Actor
import io.nook.contract.JORDAN
import io.nook.contract.aProject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The five fields no operation defines, under the names they carry on an entity. */
private val THE_FIVE = listOf("ownerSubject", "createdBy", "updatedBy", "createdByAgent", "updatedByAgent")

/**
 * What a tool call cannot say about who made it.
 *
 * Who a call is for comes from the token this request presented and what the
 * connection announced, and from nothing an agent writes into a tool's
 * arguments. Each of the five is turned back by the rule that was already there
 * for any argument a tool does not declare — this adds no rule of its own, which
 * is the point: there is no list here to forget a sixth name.
 */
class SmuggledActorTest {

    private val project = aProject()

    private val core = StandInCore().apply { projects += project }

    private fun connected(check: (io.modelcontextprotocol.client.McpSyncClient) -> Unit) {
        RunningAdapter(core).use { adapter -> check(adapter.connectedAt(project.slug)) }
    }

    private fun io.modelcontextprotocol.client.McpSyncClient.createItem(
        vararg arguments: Pair<String, Any?>,
    ): McpSchema.CallToolResult = callTool(
        McpSchema.CallToolRequest.builder("create_item").arguments(mapOf(*arguments)).build(),
    )

    @Test
    fun `each of the five sent as a tool argument is turned back naming the one at fault`() {
        connected { client ->
            THE_FIVE.forEach { field ->
                val answered = client.createItem("type" to "task", "name" to "Smuggled", field to JORDAN)

                assertEquals(true, answered.isError(), "a tool accepted $field")
                assertTrue(
                    answered.said().contains(field),
                    "the refusal does not name $field; it said: ${answered.said()}",
                )
            }
            // And the connection is none the worse for any of them.
            assertEquals(false, client.createItem("type" to "task", "name" to "Ordinary").isError())
        }
        assertEquals(1, core.toolCalls.size, "a call naming one of the five reached the core")
        assertEquals(Actor(ALEX, AN_AGENT), core.toolCalls.single().actor)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `no declared tool offers an argument for either identity`() {
        val everyArgument = declaredTools.flatMap { tool ->
            val arguments = tool.declaration.inputSchema()["properties"] as Map<String, Any>
            arguments.keys.map { "${tool.declaration.name()}.$it" }
        }

        assertEquals(
            emptyList(),
            everyArgument.filter { argument -> THE_FIVE.any { argument.endsWith(".$it") } },
            "a tool declares an argument for who a call is for",
        )
    }
}
