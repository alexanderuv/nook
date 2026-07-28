package io.nook.core.catalog

import io.nook.contract.RpcReply
import io.nook.core.DATABASE_SETTING
import io.nook.core.PORT_SETTING
import io.nook.core.db.EmbeddedPostgresSupport
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assumptions.abort

/**
 * The two programs as an operator meets them: each told its address from
 * outside, each stopping and naming what it is missing when it is not told, and
 * a call passing between them once both are up.
 *
 * These launch real processes rather than calling `main` in this one, because
 * what is under test is what a process does when it cannot start — and a
 * function that ends the process cannot be checked from inside it.
 */
class CoreProgramTest {

    private companion object {
        val db by lazy { Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase()) }

        const val CORE = "io.nook.core.MainKt"
        const val CALLER = "io.nook.core.catalog.CallOnceKt"
    }

    /**
     * Runs one of the two programs with exactly [settings] in its environment —
     * exactly, so that a setting left out is genuinely unset rather than
     * inherited from the suite running this.
     */
    private fun launch(program: String, settings: Map<String, String>): Process {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val builder = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), program)
        builder.environment().apply {
            listOf(PORT_SETTING, DATABASE_SETTING, CALLER_ADDRESS_SETTING).forEach(::remove)
            putAll(settings)
        }
        return builder.start()
    }

    private fun startedAndStopped(program: String, settings: Map<String, String>): Pair<Int, String> {
        val process = launch(program, settings)
        val complaint = process.errorStream.bufferedReader().readText()
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "$program did not stop")
        return process.exitValue() to complaint
    }

    @Test
    fun `a program started without a setting stops and names the one it is missing`() {
        mapOf(
            CORE to (PORT_SETTING to mapOf(DATABASE_SETTING to "jdbc:postgresql://localhost/unused")),
            CALLER to (CALLER_ADDRESS_SETTING to emptyMap()),
        ).forEach { (program, missing) ->
            val (setting, rest) = missing
            val (exitCode, complaint) = startedAndStopped(program, rest)

            assertTrue(exitCode != 0, "$program started anyway, without $setting")
            assertTrue(
                complaint.contains(setting),
                "$program stopped without naming $setting; it said: $complaint",
            )
        }

        // The core's other setting, on its own: a program that named only the
        // first would pass the check above and still start on a store nobody
        // chose.
        val (exitCode, complaint) = startedAndStopped(CORE, mapOf(PORT_SETTING to "1"))
        assertTrue(exitCode != 0, "the core started anyway, without $DATABASE_SETTING")
        assertTrue(complaint.contains(DATABASE_SETTING), "the core said: $complaint")
    }

    @Test
    fun `told their addresses from outside, the two programs come up and a call passes between them`() {
        val port = freePort()
        val core = launch(
            CORE,
            mapOf(
                PORT_SETTING to port.toString(),
                DATABASE_SETTING to EmbeddedPostgresSupport.freshMigratedDatabase(),
            ),
        )
        try {
            val announcement = core.inputStream.bufferedReader().readLine()
            assertTrue(
                announcement != null && announcement.contains("$LOOPBACK:$port"),
                "the core did not say where it is answering; it said: $announcement",
            )

            val caller = launch(CALLER, mapOf(CALLER_ADDRESS_SETTING to "http://$LOOPBACK:$port"))
            val answer = caller.inputStream.bufferedReader().readText()
            val complaint = caller.errorStream.bufferedReader().readText()
            assertTrue(caller.waitFor(2, TimeUnit.MINUTES), "the caller did not stop")

            assertEquals(0, caller.exitValue(), "the caller failed; it said: $complaint")
            assertTrue(answer.contains("projects"), "the caller said: $answer")
        } finally {
            core.destroy()
            core.waitFor(1, TimeUnit.MINUTES)
        }
    }

    @Test
    fun `a core bound to loopback answers there and nowhere else this machine can be reached`() {
        val elsewhere = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .toList()

        // The control. An address that cannot reach a core listening on every
        // address says nothing about what the loopback binding refuses — the
        // network turned it away, not the binding.
        val everywherePort = freePort()
        val reachable = CatalogServer(CoreCatalog(db), "0.0.0.0", everywherePort).use { everywhere ->
            everywhere.start()
            elsewhere.filter { address -> answersAt("http://$address:$everywherePort") }
        }
        if (reachable.isEmpty()) {
            abort<Unit>(
                "this machine has no address off the loopback that reaches a core listening on all of " +
                    "them ($elsewhere), so what the loopback binding refuses cannot be shown here",
            )
        }

        val loopbackPort = freePort()
        CatalogServer(CoreCatalog(db), LOOPBACK, loopbackPort).use { onlyHere ->
            val address = onlyHere.start()

            assertEquals(
                emptyList(),
                reachable.filter { answersAt("http://$it:$loopbackPort") },
                "a core bound to the loopback address answered somewhere else",
            )
            // And on the loopback address it answers, with no credential asked
            // for and none presented — which is the whole of the arrangement.
            assertIs<RpcReply.Answered>(rawReply(address, rawCall("list_projects")))
        }
    }

    /** Whether a well-formed call to [address] is served at all, bounded so an unroutable address cannot hang this. */
    private fun answersAt(address: String): Boolean = runCatching {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val request = HttpRequest.newBuilder(URI.create(address))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(rawCall("list_projects")))
            .build()
        client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200
    }.getOrDefault(false)
}
