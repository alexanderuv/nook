package io.nook.mcp

import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import io.nook.contract.Project
import io.nook.contract.aProject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Which project a connection is for, and how it knows.
 *
 * Everything here is about the adapter rather than about a tool: what a connection
 * is told on opening, how many times the core is asked, and which project the
 * calls made on a connection act inside.
 */
class ConnectedProjectTest {

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

    /** What the connection was told, checked against the four things the core holds for [project]. */
    private fun McpSyncClient.wasToldAbout(project: Project) {
        val said = serverInstructions
        listOf(project.id.toString(), project.slug, project.name, project.description.orEmpty()).forEach {
            assertTrue(said.contains(it), "the connection was not told '$it'; it was told: $said")
        }
    }

    private fun McpSyncClient.getItem(ref: String): McpSchema.CallToolResult =
        callTool(McpSchema.CallToolRequest.builder("get_item").arguments(mapOf("ref" to ref)).build())

    private fun McpSyncClient.listItems(): McpSchema.CallToolResult =
        callTool(McpSchema.CallToolRequest.builder("list_items").arguments(emptyMap()).build())

    @Test
    fun `a connection at a project's handle and one at its id are told the same four things about it`() {
        RunningAdapter(core).use { adapter ->
            val byHandle = adapter.connectedAt(searchRevamp.slug)
            val byId = adapter.connectedAt(searchRevamp.id.toString())

            byHandle.wasToldAbout(searchRevamp)
            byId.wasToldAbout(searchRevamp)
            assertEquals(
                byHandle.serverInstructions,
                byId.serverInstructions,
                "the handle and the id reached the same project but were told different things",
            )
        }
    }

    @Test
    fun `each project's connections are told their own project and nothing of the other's`() {
        RunningAdapter(core).use { adapter ->
            val onSearch = adapter.connectedAt(searchRevamp.slug)
            val onBilling = adapter.connectedAt(billing.slug)

            onSearch.wasToldAbout(searchRevamp)
            onBilling.wasToldAbout(billing)
            assertFalse(
                onSearch.serverInstructions.contains(billing.id.toString()),
                "one project's connection was told another project's id",
            )
            assertFalse(
                onBilling.serverInstructions.contains(searchRevamp.id.toString()),
                "one project's connection was told another project's id",
            )
        }
    }

    @Test
    fun `the core is asked which project an address names once per connection, not once per call`() {
        RunningAdapter(core).use { adapter ->
            val client = adapter.connectedAt(searchRevamp.slug)
            client.listTools()
            repeat(3) { client.getItem("add-search") }

            assertEquals(
                listOf("search-revamp"),
                core.projectQuestions.map { it.values.single() },
                "the address was resolved more than once, or somewhere other than when the connection opens",
            )
            assertEquals(3, core.toolCalls.size, "the calls did not all reach the core")
        }
    }

    @Test
    fun `every call reaches the core naming its own connection's project, by id`() {
        RunningAdapter(core).use { adapter ->
            val onSearch = adapter.connectedAt(searchRevamp.slug)
            val onBilling = adapter.connectedAt(billing.slug)

            onSearch.getItem("add-search")
            onBilling.getItem("send-invoice")
            onSearch.listItems()

            assertEquals(
                listOf(searchRevamp.id, billing.id, searchRevamp.id).map(Uuid::toString),
                core.toolCalls.map { it.project },
                "a call acted inside a project that was not its connection's, or inside the words of an address",
            )
        }
    }

    @Test
    fun `naming an entity of another project leaves the connection where it was`() {
        val anItemOfBilling = "cccccccc-0000-0000-0000-0000000000b1"
        RunningAdapter(core).use { adapter ->
            val onSearch = adapter.connectedAt(searchRevamp.slug)

            onSearch.getItem(anItemOfBilling)
            onSearch.getItem("add-search")

            assertEquals(
                listOf(searchRevamp.id.toString(), searchRevamp.id.toString()),
                core.toolCalls.map { it.project },
                "a reference to another project's item moved the connection",
            )
            assertEquals(
                listOf(anItemOfBilling),
                core.toolCalls.first().values,
                "the reference did not reach the core as it was written",
            )
        }
    }
}
