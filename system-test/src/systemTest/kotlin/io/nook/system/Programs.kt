package io.nook.system

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The port the core answers its adapters on. */
internal const val CORE_PORT: String = "NOOK_CORE_PORT"

/** Where the structure store is, as a JDBC URL. */
internal const val DATABASE_URL: String = "NOOK_DATABASE_URL"

/** The port the MCP server answers on. */
internal const val MCP_PORT: String = "NOOK_MCP_PORT"

/** The port the web app answers on. */
internal const val WEB_PORT: String = "NOOK_WEB_PORT"

/** Where the core is, as the address its connection answers at. */
internal const val CORE_ADDRESS: String = "NOOK_CORE_ADDRESS"

/** What a token presented at an adapter is checked against. */
internal const val TOKEN_SECRET: String = "NOOK_TOKEN_SECRET"

/**
 * These names are written out here rather than taken from the three programs,
 * because they are what an operator writes and this run is the operator. A
 * rename in a program stops that program with a complaint naming the setting it
 * wanted, which is exactly how it should surface.
 */
private const val SETTING_PREFIX = "NOOK_"

/** Where the start script looks for a JVM before it falls back to the machine's own. */
private const val JAVA_HOME = "JAVA_HOME"

/** How long a program is given to say where it is answering. */
private val COMING_UP: Duration = 60.seconds

/** How long a program is given to stop once it has been asked to. */
private val STOPPING: Duration = 60.seconds

/** How often the announcement is looked for while a program is starting. */
private val BETWEEN_LOOKS: Duration = 50.milliseconds

/**
 * One of the three programs, running as its own process out of its own
 * distribution.
 *
 * The process is held rather than the program being one, because two checks stop
 * a program and start it again at the same address — so what runs lives shorter
 * than what a caller holds on to.
 *
 * What the program says and what it complains go to files rather than down
 * pipes. A pipe nobody is draining fills, and a program blocked on writing to
 * one looks exactly like a program that has stopped answering.
 */
internal class Program(
    private val distribution: Distribution,
    private val settings: Map<String, String>,
) : AutoCloseable {

    private val said: Path = Files.createTempFile("nook-$distribution-", ".said")

    private val complained: Path = Files.createTempFile("nook-$distribution-", ".complained")

    private var running: Process? = null

    /**
     * Starts the program and hands back the line it prints to say where it is
     * answering — so a caller that gets past here knows it is serving, rather
     * than merely launched.
     */
    fun start(): String {
        check(running == null) { "$distribution is already running" }
        val process = launch(distribution, settings, said, complained)
        running = process
        return process.announcement()
    }

    /** Asks the program to stop and waits for it to be gone. Stopping a stopped program does nothing. */
    fun stop() {
        val process = running ?: return
        running = null
        process.destroy()
        check(process.waitFor(STOPPING.inWholeSeconds, TimeUnit.SECONDS)) { "$distribution would not stop" }
    }

    override fun close() {
        stop()
        said.deleteIfExists()
        complained.deleteIfExists()
    }

    private fun Process.announcement(): String {
        val giveUpAt = System.nanoTime() + COMING_UP.inWholeNanoseconds
        while (System.nanoTime() < giveUpAt) {
            // A line is only a line once it has ended. This reads the file while
            // the program is still writing it, and half an announcement holds
            // half an address.
            val written = said.readText()
            val ended = written.indexOf('\n')
            if (ended >= 0) return written.substring(0, ended)
            if (!isAlive) break
            Thread.sleep(BETWEEN_LOOKS.inWholeMilliseconds)
        }
        error("$distribution never said where it is answering. It complained: ${complained.readText()}")
    }
}

/** What a program that stopped by itself left behind. */
internal data class Stopped(val exitCode: Int, val complaint: String)

/**
 * Starts [distribution] with exactly [settings] and waits for it to stop.
 *
 * For the checks about a program that cannot start, which only a real process
 * can be asked: a function that ends the process cannot be called from inside
 * the process asking the question.
 */
internal fun startedAndStopped(distribution: Distribution, settings: Map<String, String>): Stopped {
    val said = Files.createTempFile("nook-$distribution-", ".said")
    val complained = Files.createTempFile("nook-$distribution-", ".complained")
    try {
        val process = launch(distribution, settings, said, complained)
        check(process.waitFor(STOPPING.inWholeSeconds, TimeUnit.SECONDS)) { "$distribution did not stop" }
        return Stopped(process.exitValue(), complained.readText())
    } finally {
        said.deleteIfExists()
        complained.deleteIfExists()
    }
}

private fun launch(
    distribution: Distribution,
    settings: Map<String, String>,
    said: Path,
    complained: Path,
): Process {
    val builder = ProcessBuilder(distribution.startScript.path)
        .redirectOutput(ProcessBuilder.Redirect.to(said.toFile()))
        .redirectError(ProcessBuilder.Redirect.to(complained.toFile()))
    builder.environment().apply {
        // Exactly the settings asked for: one left out of the map has to be
        // genuinely unset rather than inherited from the suite running this.
        keys.removeAll { it.startsWith(SETTING_PREFIX) }
        // The start script takes its JVM from here, or from whatever `java` the
        // machine's PATH resolves. Written in, the program runs on the JVM the
        // build pins; left out, it runs on whatever the machine happens to have,
        // and nothing about the run would say which.
        put(JAVA_HOME, System.getProperty("java.home"))
        putAll(settings)
    }
    return builder.start()
}
