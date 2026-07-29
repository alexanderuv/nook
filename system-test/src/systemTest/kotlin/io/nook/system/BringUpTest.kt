package io.nook.system

import io.nook.contract.ALEX
import io.nook.contract.THE_SECRET
import io.nook.contract.presenting
import io.nook.contract.tokenFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The agent the connections in this run announce themselves as. */
internal const val AN_AGENT: String = "claude-code"

/**
 * The three programs coming up as an operator brings them up: each started from
 * its own distribution, each told its settings from outside, and neither order
 * of starting them arranged by anybody.
 *
 * Which comes up first is the question. Both adapters reach the core through a
 * calling library that outlives a core which is not there yet, so a system
 * assembled the awkward way round must serve without anything being restarted —
 * otherwise every deployment carries an ordering nobody wrote down.
 */
class BringUpTest {

    @Test
    fun `the two adapters come up before the core, and are both served once it is up`() {
        AssembledSystem().use { system ->
            system.mcp.start()
            system.web.start()
            system.core.start()

            bothAdaptersServe(system)
        }
    }

    @Test
    fun `the core comes up first, and the same calls are served`() {
        AssembledSystem().use { system ->
            system.core.start()
            system.mcp.start()
            system.web.start()

            bothAdaptersServe(system)
        }
    }

    @Test
    fun `a program started without one of its settings stops and names the one it is missing`() {
        mapOf(
            Distribution.CORE to mapOf(
                CORE_PORT to "1",
                DATABASE_URL to "jdbc:postgresql://$LOOPBACK/nothing-is-there",
            ),
            Distribution.MCP to mapOf(
                MCP_PORT to "1",
                CORE_ADDRESS to "http://$LOOPBACK:1",
                TOKEN_SECRET to THE_SECRET,
            ),
            Distribution.WEB to mapOf(
                WEB_PORT to "1",
                CORE_ADDRESS to "http://$LOOPBACK:1",
                TOKEN_SECRET to THE_SECRET,
            ),
        ).forEach { (program, whole) ->
            whole.keys.forEach { missing ->
                val stopped = startedAndStopped(program, whole - missing)

                assertTrue(stopped.exitCode != 0, "$program started anyway, without $missing")
                assertTrue(
                    stopped.complaint.contains(missing),
                    "$program stopped without naming $missing; it complained: ${stopped.complaint}",
                )
            }
        }
    }

    /**
     * A project made through the web API and a connection opened at its address
     * over MCP, both served against the same running core — with nothing
     * restarted and no client rebuilt after the fact.
     */
    private fun bothAdaptersServe(system: AssembledSystem) {
        val project = WebApiCaller(system.webApi, presenting(tokenFor(ALEX))).use { caller ->
            caller.answered("create_project", buildJsonObject { put("name", "a project made while bringing up") })
        }
        val slug = project.jsonObject.getValue("slug").jsonPrimitive.content

        system.connectionFor(slug, AN_AGENT, tokenFor(ALEX)).use { connection ->
            assertTrue(
                connection.serverInstructions.contains(slug),
                "the connection was not told its project; it was told: ${connection.serverInstructions}",
            )
            // And a tool call reaches the core and comes back, which is the
            // whole of what the two adapters being up together has to mean. The
            // project was made a moment ago, so the listing is empty and that is
            // the answer being checked for.
            assertEquals(
                JsonArray(emptyList()),
                connection.answered("list_items"),
                "a tool call was not served against the project the other adapter made",
            )
        }
    }
}
