package io.nook.mcp

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import io.nook.contract.aProject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.abort

/** What a well-formed opening exchange is answered with where the front door is listening. */
private const val SERVED = 200

/** The protocol's own number for a call that reached no verdict at all. */
private const val NO_VERDICT = -32603

/**
 * The front door as an operator meets it: told its two addresses from outside,
 * stopping and naming what it is missing when it is not told, and serving an
 * ordinary client against a core reached over a real connection.
 *
 * The first checks launch a real process rather than calling `main` in this
 * one, because what is under test is what a process does when it cannot start —
 * and a function that ends the process cannot be checked from inside it.
 */
class ToolProgramTest {

    private companion object {
        const val PROGRAM = "io.nook.mcp.MainKt"
    }

    private val project = aProject()

    private val core = StandInCore().apply { projects += project }

    /**
     * Runs the program with exactly [settings] in its environment — exactly, so
     * that a setting left out is genuinely unset rather than inherited from the
     * suite running this.
     */
    private fun launch(settings: Map<String, String>): Process {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val builder = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), PROGRAM)
        builder.environment().apply {
            listOf(PORT_SETTING, CORE_ADDRESS_SETTING).forEach(::remove)
            putAll(settings)
        }
        return builder.start()
    }

    /** Starts the program against a core at [corePort], runs [check] against its address, and stops it. */
    private fun running(corePort: Int, check: (String) -> Unit) {
        val port = freePort()
        val program = launch(
            mapOf(PORT_SETTING to port.toString(), CORE_ADDRESS_SETTING to "http://$LOOPBACK:$corePort"),
        )
        try {
            val announcement = program.inputStream.bufferedReader().readLine()
            assertTrue(
                announcement != null && announcement.contains("$LOOPBACK:$port"),
                "the program did not say where it is answering; it said: $announcement",
            )
            check("http://$LOOPBACK:$port")
        } finally {
            program.destroy()
            program.waitFor(1, TimeUnit.MINUTES)
        }
    }

    private fun clientAt(address: String, projectRef: String): McpSyncClient =
        McpClient.sync(HttpClientStreamableHttpTransport.builder(address).endpoint("$TOOLS_PATH/$projectRef").build())
            .build()

    private fun McpSyncClient.getItem(): McpSchema.CallToolResult =
        callTool(McpSchema.CallToolRequest.builder("get_item").arguments(mapOf("ref" to "add-search")).build())

    @Test
    fun `a program started without a setting stops and names the one it is missing`() {
        listOf(
            PORT_SETTING to mapOf(CORE_ADDRESS_SETTING to "http://$LOOPBACK:1"),
            CORE_ADDRESS_SETTING to mapOf(PORT_SETTING to "1"),
        ).forEach { (missing, rest) ->
            val program = launch(rest)
            val complaint = program.errorStream.bufferedReader().readText()
            assertTrue(program.waitFor(2, TimeUnit.MINUTES), "the program did not stop without $missing")

            assertTrue(program.exitValue() != 0, "the program started anyway, without $missing")
            assertTrue(complaint.contains(missing), "it stopped without naming $missing; it said: $complaint")
        }
    }

    @Test
    fun `told both addresses, a client connects and calls a tool against the core`() {
        val corePort = freePort()
        StandInCoreHost(core, corePort).use { served ->
            served.start()
            running(corePort) { address ->
                clientAt(address, project.slug).use { client ->
                    client.initialize()

                    assertTrue(
                        client.serverInstructions.contains(project.id.toString()),
                        "the connection was not told its project; it was told: ${client.serverInstructions}",
                    )
                    assertEquals(false, client.getItem().isError(), "a tool call was not served")
                    assertEquals(project.id.toString(), core.toolCalls.single().project)
                }
            }
        }
    }

    @Test
    fun `a core that goes away and comes back leaves the connection usable, with nothing restarted`() {
        val corePort = freePort()
        StandInCoreHost(core, corePort).use { served ->
            served.start()
            running(corePort) { address ->
                clientAt(address, project.slug).use { client ->
                    client.initialize()
                    assertEquals(false, client.getItem().isError(), "the first call was not served")

                    served.stop()
                    val broke = assertFailsWith<McpError> { client.getItem() }
                    assertEquals(NO_VERDICT, broke.jsonRpcError.code(), "a core that is not there was not a breakdown")

                    served.start()
                    assertEquals(
                        false,
                        client.getItem().isError(),
                        "the connection never recovered, though the core came back and neither end was rebuilt",
                    )
                }
            }
        }
    }

    @Test
    fun `a front door bound to loopback answers there and nowhere else this machine can be reached`() {
        val elsewhere = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .toList()

        // The control. An address that cannot reach a door listening on every
        // address says nothing about what the loopback binding refuses — the
        // network turned it away, not the binding.
        val everywherePort = freePort()
        val reachable = Dispatcher(core).use { dispatcher ->
            ToolServer(dispatcher, "0.0.0.0", everywherePort).use { everywhere ->
                everywhere.start()
                elsewhere.filter { openedAt("http://$it:$everywherePort$TOOLS_PATH/${project.slug}") != null }
            }
        }
        if (reachable.isEmpty()) {
            abort<Unit>(
                "this machine has no address off the loopback that reaches a front door listening on all of " +
                    "them ($elsewhere), so what the loopback binding refuses cannot be shown here",
            )
        }

        val loopbackPort = freePort()
        Dispatcher(core).use { dispatcher ->
            ToolServer(dispatcher, LOOPBACK, loopbackPort).use { onlyHere ->
                onlyHere.start()

                assertEquals(
                    emptyList(),
                    reachable.filter { openedAt("http://$it:$loopbackPort$TOOLS_PATH/${project.slug}") != null },
                    "a front door bound to the loopback address answered somewhere else",
                )
                // And on the loopback address it answers, with no credential
                // asked for and none presented, which is the whole arrangement.
                assertEquals(
                    SERVED,
                    openedAt("http://$LOOPBACK:$loopbackPort$TOOLS_PATH/${project.slug}")?.status,
                    "a front door bound to the loopback address did not answer there",
                )
            }
        }
    }
}
