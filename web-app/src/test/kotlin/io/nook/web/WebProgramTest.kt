package io.nook.web

import io.nook.contract.Project
import io.nook.contract.RpcCode
import io.nook.contract.RpcReply
import io.nook.contract.RpcReplySerializer
import io.nook.contract.aProject
import io.nook.contract.catalogJson
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assumptions.abort

/**
 * The app as an operator meets it: told its two addresses from outside,
 * stopping and naming what it is missing when it is not told, and serving an
 * ordinary caller against a core reached over a real connection.
 *
 * The first checks launch a real process rather than calling `main` in this
 * one, because what is under test is what a process does when it cannot start —
 * and a function that ends the process cannot be checked from inside it.
 */
class WebProgramTest {

    private companion object {
        const val PROGRAM = "io.nook.web.MainKt"

        /** A caller this surface is built for, and one that is a page in a browser. */
        const val A_PAGE = "http://localhost:5173"
    }

    private val core = StandInCore()

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

    /** Starts the program against a core at [corePort], runs [check] against its one address, and stops it. */
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
            check("http://$LOOPBACK:$port$API_PATH")
        } finally {
            program.destroy()
            program.waitFor(1, TimeUnit.MINUTES)
        }
    }

    private fun reply(address: String, body: String): RpcReply =
        catalogJson.decodeFromString(RpcReplySerializer, sentTo(address, body).said)

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
    fun `told both addresses, a caller reaches an operation and back`() {
        val corePort = freePort()
        CoreConnection(core, corePort).use { served ->
            served.start()
            running(corePort) { api ->
                val answered = assertIs<RpcReply.Answered>(reply(api, rawCall("list_projects")))
                assertEquals(
                    catalogJson.encodeToJsonElement(ListSerializer(Project.serializer()), listOf(aProject())),
                    answered.result,
                )
                assertEquals(1, core.invocations.size, "the call did not reach the core exactly once")
            }
        }
    }

    @Test
    fun `a core that was not there, then is, then is not, then is, leaves the app serving throughout`() {
        val corePort = freePort()
        CoreConnection(core, corePort).use { served ->
            running(corePort) { api ->
                // Started before the core: which of the two comes up first is
                // nobody's business to arrange.
                val first = assertIs<RpcReply.Failed>(reply(api, rawCall("list_projects")))
                assertEquals(RpcCode.INTERNAL_ERROR, first.error.code)

                served.start()
                assertIs<RpcReply.Answered>(reply(api, rawCall("list_projects")), "the app never found the core")

                served.stop()
                assertIs<RpcReply.Failed>(reply(api, rawCall("list_projects")))

                served.start()
                assertIs<RpcReply.Answered>(
                    reply(api, rawCall("list_projects")),
                    "the app never recovered, though the core came back and nothing was restarted",
                )
            }
        }
    }

    @Test
    fun `a request carrying the address of a page as its sender is served exactly as one carrying none`() {
        // A browser attaches this to every request it sends. No half of a check
        // on it is built here: the gate arrives with sign-in, and until then a
        // page open on this machine is as welcome as any other local caller —
        // which is exactly what makes revisiting that a condition of this
        // surface reaching real users.
        val corePort = freePort()
        CoreConnection(core, corePort).use { served ->
            served.start()
            running(corePort) { api ->
                val body = rawCall("list_projects")
                assertEquals(sentTo(api, body), sentTo(api, body, from = A_PAGE))
            }
        }
    }

    @Test
    fun `an app bound to loopback answers there and nowhere else this machine can be reached`() {
        val elsewhere = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .toList()

        val call = rawCall("list_projects")

        // The control. An address that cannot reach an app listening on every
        // address says nothing about what the loopback binding refuses — the
        // network turned it away, not the binding.
        val everywherePort = freePort()
        val reachable = WebApi(core, "0.0.0.0", everywherePort).use { everywhere ->
            everywhere.start()
            elsewhere.filter { reachedAt("http://$it:$everywherePort$API_PATH", call) != null }
        }
        if (reachable.isEmpty()) {
            abort<Unit>(
                "this machine has no address off the loopback that reaches an app listening on all of " +
                    "them ($elsewhere), so what the loopback binding refuses cannot be shown here",
            )
        }

        val loopbackPort = freePort()
        WebApi(core, LOOPBACK, loopbackPort).use { onlyHere ->
            onlyHere.start()

            assertEquals(
                emptyList(),
                reachable.filter { reachedAt("http://$it:$loopbackPort$API_PATH", call) != null },
                "an app bound to the loopback address answered somewhere else",
            )
            // And on the loopback address it answers, with no credential asked
            // for and none presented, which is the whole arrangement.
            assertEquals(
                200,
                reachedAt("http://$LOOPBACK:$loopbackPort$API_PATH", call)?.status,
                "an app bound to the loopback address did not answer there",
            )
        }
    }
}
