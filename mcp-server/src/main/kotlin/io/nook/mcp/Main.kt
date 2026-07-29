package io.nook.mcp

import io.nook.contract.BearerTokens
import io.nook.contract.CatalogClient
import io.nook.contract.UnusableSecretException
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/** The port the agent's front door answers on. */
const val PORT_SETTING: String = "NOOK_MCP_PORT"

/** Where the core is, as the address its connection answers at. */
const val CORE_ADDRESS_SETTING: String = "NOOK_CORE_ADDRESS"

/** What a token presented at this door is checked against. */
const val TOKEN_SECRET_SETTING: String = "NOOK_TOKEN_SECRET"

/**
 * The agent's front door as a program: the connection it reaches the core by,
 * and the addresses it serves projects at.
 *
 * Three settings come from outside, and none has a default. A default port
 * would have this listening somewhere nobody chose, a default core address
 * would have it calling something nobody chose, and a default secret would have
 * it accepting tokens nobody vouched for — so a missing setting stops the
 * program and says which one, rather than starting on a guess.
 *
 * The reading of a token is built here, before anything is served, so that a
 * secret nothing could check a token against stops the program at startup
 * rather than at the first call. What the library says when it refuses one is
 * about key length; what an operator needs to read is which setting to fix, so
 * the words are written here.
 *
 * The host is not among the settings, for the reason [ToolServer] gives: the
 * loopback binding is what keeps a caller on another machine off a connection
 * that travels in the clear, and a host taken from a setting is one typo away
 * from removing it.
 *
 * The core need not be up for this to start. Nothing is called until a
 * connection opens, and the calling library outlives a core that is not there
 * yet — so which of the two is started first is nobody's business to arrange.
 */
fun main() {
    val port = requiredSetting(PORT_SETTING).toIntOrNull()
        ?: stop("$PORT_SETTING must be a port number")
    val tokens = try {
        BearerTokens(requiredSetting(TOKEN_SECRET_SETTING))
    } catch (couldCheckNothing: UnusableSecretException) {
        stop("$TOKEN_SECRET_SETTING is not a secret this could check a token against")
    }
    val core = CatalogClient(requiredSetting(CORE_ADDRESS_SETTING))
    val dispatcher = Dispatcher(core)
    val server = ToolServer(Gate(tokens, dispatcher), LOOPBACK, port)

    println("the agent's front door is answering on ${server.start()}$TOOLS_PATH/{project}")

    // Nothing more for this thread to do. The process runs until it is asked to
    // stop, and lets go of the port, the connections and the core on the way out.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.close()
            dispatcher.close()
            core.close()
        },
    )
    CountDownLatch(1).await()
}

private fun requiredSetting(name: String): String =
    System.getenv(name) ?: stop("$name is not set, and there is no default for it")

private fun stop(why: String): Nothing {
    System.err.println(why)
    exitProcess(1)
}
