package io.nook.mcp

import io.nook.contract.CatalogClient
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/** The port the agent's front door answers on. */
const val PORT_SETTING: String = "NOOK_MCP_PORT"

/** Where the core is, as the address its connection answers at. */
const val CORE_ADDRESS_SETTING: String = "NOOK_CORE_ADDRESS"

/**
 * The agent's front door as a program: the connection it reaches the core by,
 * and the addresses it serves projects at.
 *
 * Two settings come from outside, and neither has a default. A default port
 * would have this listening somewhere nobody chose, and a default core address
 * would have it calling something nobody chose — so a missing setting stops the
 * program and says which one, rather than starting on a guess.
 *
 * The host is not among them, for the reason [ToolServer] gives: the loopback
 * binding is the whole of what keeps a caller on another machine out of a
 * connection that asks for no credential.
 *
 * The core need not be up for this to start. Nothing is called until a
 * connection opens, and the calling library outlives a core that is not there
 * yet — so which of the two is started first is nobody's business to arrange.
 */
fun main() {
    val port = requiredSetting(PORT_SETTING).toIntOrNull()
        ?: stop("$PORT_SETTING must be a port number")
    val core = CatalogClient(requiredSetting(CORE_ADDRESS_SETTING))
    val dispatcher = Dispatcher(core)
    val server = ToolServer(dispatcher, LOOPBACK, port)

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
