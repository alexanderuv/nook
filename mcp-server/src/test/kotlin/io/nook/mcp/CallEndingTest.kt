package io.nook.mcp

import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import io.nook.contract.ErrorCode
import io.nook.contract.ProjectItem
import io.nook.contract.REASON
import io.nook.contract.Release
import io.nook.contract.StructuredError
import io.nook.contract.StructuredErrorException
import io.nook.contract.asRpcError
import io.nook.contract.asStructuredError
import io.nook.contract.catalogJson
import io.nook.contract.rpcCode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/** The protocol's own number for a fault inside the server, and for a call it cannot route. */
private const val SOMETHING_BROKE = -32603

private const val NO_SUCH_TOOL = -32602

/**
 * The three ways a call can end, and an agent's ability to tell them apart.
 *
 * Each ending is followed by the same two questions, because both are things
 * that go wrong quietly: did exactly one request reach the core — nothing sent
 * again on the agent's behalf — and is the connection still usable afterwards.
 */
class CallEndingTest {

    private val project = aProject()

    private val core = StandInCore().apply { projects += project }

    private val door = FrontDoor(core)

    private val client = door.connectedAt(project.slug)

    @AfterTest
    fun letGo() {
        door.close()
    }

    /** What the core does with the next call, with the record of what reached it wiped clean. */
    private fun coreWill(answering: (Invocation) -> Any?) {
        core.answering = answering
        core.invocations.clear()
    }

    private fun call(tool: String, vararg arguments: Pair<String, Any?>): McpSchema.CallToolResult =
        client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(mapOf(*arguments)).build())

    private fun getItem(): McpSchema.CallToolResult = call("get_item", "ref" to "add-search")

    /** Exactly one request reached the core, and the connection is still good for another. */
    private fun oneRequestReached() {
        assertEquals(1, core.toolCalls.size, "the core was asked ${core.toolCalls.size} times for one call")
        coreWill(::somethingOfTheRightShape)
        assertEquals(false, getItem().isError(), "the next call on the connection was not served normally")
    }

    @Test
    fun `each refusal the core makes arrives as a failed call carrying its own code, message and data`() {
        ErrorCode.entries.forEach { code ->
            val refusal = StructuredError(
                code = code,
                message = "the core turned this down as ${code.label}",
                details = mapOf("ref" to "add-search", "why" to code.label),
            )
            coreWill { throw StructuredErrorException(refusal) }

            val answered = getItem()

            assertEquals(true, answered.isError(), "a refusal did not arrive as a failed call")
            val carried = answered.asRefusal()
            assertEquals(refusal.asRpcError(), carried, "the core's own words did not survive the crossing")
            // The number is the standard's; the name of the failure rides
            // beside it, so an agent reads which one it was without matching
            // integers against a table.
            assertEquals(code.rpcCode, carried.code)
            assertEquals(code.label, carried.data?.get(REASON)?.let { (it as JsonPrimitive).content })
            assertEquals(refusal, carried.asStructuredError(), "the refusal did not read back as the core's own")
            oneRequestReached()
        }
    }

    @Test
    fun `a fault inside the core arrives as a breakdown carrying none of the four refusal codes`() {
        coreWill { error("something inside the core came apart") }

        val broke = assertFailsWith<McpError> { getItem() }

        assertEquals(SOMETHING_BROKE, broke.jsonRpcError.code(), "a fault did not arrive as a fault of the protocol's")
        val said = "${broke.jsonRpcError.message()} ${broke.jsonRpcError.data()}"
        ErrorCode.entries.forEach {
            assertFalse(said.contains(it.label), "a breakdown read as a refusal an agent could act on: $said")
        }
        oneRequestReached()
    }

    @Test
    fun `a deletion succeeds carrying no entity, and is not mistakable for a refusal`() {
        coreWill { null }

        val answered = call("delete_item", "ref" to "add-search")

        assertEquals(false, answered.isError(), "a deletion that worked arrived as a failure")
        assertEquals(emptyList(), answered.content(), "a deletion carried an entity")
        assertNull(answered.structuredContent(), "a deletion carried an entity")
        oneRequestReached()
    }

    @Test
    fun `a tool the server does not offer comes back naming the tool that was asked for`() {
        listOf("create_project", "delete_project", "summon_a_unicorn").forEach { unoffered ->
            coreWill(::somethingOfTheRightShape)

            val turnedBack = assertFailsWith<McpError> { call(unoffered, "ref" to "add-search") }

            assertEquals(NO_SUCH_TOOL, turnedBack.jsonRpcError.code())
            val said = "${turnedBack.jsonRpcError.message()} ${turnedBack.jsonRpcError.data()}"
            assertTrue(said.contains(unoffered), "the failure does not name the tool asked for; it said: $said")
            assertEquals(emptyList(), core.toolCalls, "a tool that does not exist reached the core")
        }
        coreWill(::somethingOfTheRightShape)
        assertEquals(false, getItem().isError(), "the next call on the connection was not served normally")
    }

    @Test
    fun `an answer carries what the core produced, whole`() {
        // Both entity kinds an agent can be handed, each compared as the whole
        // thing rather than as a chosen few fields — so one added later is
        // covered without this being edited.
        val item = anItem()
        coreWill { item }
        assertEquals(
            catalogJson.encodeToString(ProjectItem.serializer(), item),
            getItem().said(),
            "the item did not come back as the contract writes it",
        )

        val release = aRelease()
        coreWill { release }
        assertEquals(
            catalogJson.encodeToString(Release.serializer(), release),
            call("create_release", "name" to release.name).said(),
            "the release did not come back as the contract writes it",
        )
        oneRequestReached()
    }
}
