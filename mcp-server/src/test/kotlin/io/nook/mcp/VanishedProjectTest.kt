package io.nook.mcp

import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import io.nook.contract.ErrorCode
import io.nook.contract.Missing
import io.nook.contract.RpcCode
import io.nook.contract.StructuredError
import io.nook.contract.StructuredErrorException
import io.nook.contract.aProject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.Uuid

/**
 * A project deleted under a connected agent.
 *
 * A connection outlives the thing it names — someone can delete a project on
 * the web surface while an agent sits on an open connection for hours — and
 * nothing checks the address again before every call. What keeps the connection
 * honest is the core's own answer to an ordinary call, which is what these
 * checks drive.
 */
class VanishedProjectTest {

    private val project = aProject()

    private val core = StandInCore().apply { projects += project }

    private fun McpSyncClient.getItem(): McpSchema.CallToolResult =
        callTool(McpSchema.CallToolRequest.builder("get_item").arguments(mapOf("ref" to "add-search")).build())

    /** One call that must not be served at all — no answer, and no refusal either. */
    private fun McpSyncClient.isTurnedAway(what: String) {
        runCatching { getItem() }.onSuccess { fail("$what was served; it answered: ${it.said()}") }
    }

    @Test
    fun `a refusal saying the project is gone ends the connection, after telling the agent why`() {
        RunningAdapter(core).use { adapter ->
            val client = adapter.connectedAt(project.slug)
            assertEquals(false, client.getItem().isError(), "the connection was not serving to begin with")

            core.projects.remove(project)

            val told = client.getItem()
            assertEquals(true, told.isError(), "the call after the deletion was not refused")
            assertEquals(RpcCode.NOT_FOUND, told.asRefusal().code)
            assertEquals(Missing.PROJECT, Missing.of(told.asRefusal()), "the refusal does not say the project is gone")

            core.invocations.clear()
            client.isTurnedAway("a call after the project was found to be gone")
            assertEquals(emptyList(), core.toolCalls, "a call on a connection that should have ended reached the core")
        }
    }

    @Test
    fun `every connection to that project ends, not only the one that found out`() {
        RunningAdapter(core).use { adapter ->
            val whoFindsOut = adapter.connectedAt(project.slug)
            val theOther = adapter.connectedAt(project.id.toString())

            core.projects.remove(project)
            assertEquals(true, whoFindsOut.getItem().isError())

            core.invocations.clear()
            theOther.isTurnedAway("a second connection to the same project")
            assertEquals(emptyList(), core.toolCalls)
        }
    }

    @Test
    fun `a refusal about an item leaves the connection serving`() {
        RunningAdapter(core).use { adapter ->
            val client = adapter.connectedAt(project.slug)
            core.answering = {
                throw StructuredErrorException(
                    StructuredError(ErrorCode.NOT_FOUND, "no item matches that", Missing.ITEM.asDetails()),
                )
            }

            val told = client.getItem()
            assertEquals(Missing.ITEM, Missing.of(told.asRefusal()))

            core.answerNormally()
            assertEquals(false, client.getItem().isError(), "a missing item ended a connection it had no business ending")
        }
    }

    @Test
    fun `a connection opened afterwards at the same address is refused naming the project`() {
        RunningAdapter(core).use { adapter ->
            adapter.connectedAt(project.slug).getItem()
            core.projects.remove(project)

            val answered = adapter.openingAt("$TOOLS_PATH/${project.slug}")

            assertEquals(404, answered.status)
            assertTrue(
                answered.said.contains(project.slug),
                "a fresh connection was refused without naming the project; it said: ${answered.said}",
            )
        }
    }

    @Test
    fun `a handle given to a new project never carries the old connection's calls`() {
        RunningAdapter(core).use { adapter ->
            val old = adapter.connectedAt(project.slug)

            // Deleted, and the handle it freed given to a project of its own.
            core.projects.remove(project)
            val replacement = aProject(id = Uuid.parse("aaaaaaaa-0000-0000-0000-00000000000f"))
            core.projects += replacement

            assertEquals(true, old.getItem().isError(), "the first call after the deletion was not refused")
            val fresh = adapter.connectedAt(project.slug)
            assertTrue(
                fresh.serverInstructions.contains(replacement.id.toString()),
                "a connection opened at the freed handle did not reach the project that now holds it",
            )

            core.invocations.clear()
            old.isTurnedAway("a call from the connection whose project was replaced")
            assertEquals(
                emptyList(),
                core.toolCalls,
                "a call of the old connection's was carried out against the project that took its handle",
            )
            assertEquals(false, fresh.getItem().isError(), "the connection to the new project was not serving")
        }
    }
}
