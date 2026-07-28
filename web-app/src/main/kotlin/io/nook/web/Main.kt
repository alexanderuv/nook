package io.nook.web

import io.nook.contract.CatalogClient
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/** The port the app the web UI reaches answers on. */
const val PORT_SETTING: String = "NOOK_WEB_PORT"

/** Where the core is, as the address its connection answers at. */
const val CORE_ADDRESS_SETTING: String = "NOOK_CORE_ADDRESS"

/**
 * The human surface's front door as a program: the connection it reaches the
 * core by, and the one address it serves the eleven operations at.
 *
 * Two settings come from outside, and neither has a default. A default port
 * would have this listening somewhere nobody chose, and a default core address
 * would have it calling something nobody chose — so a missing setting stops the
 * program and says which one, rather than starting on a guess.
 *
 * The host is not among them, for the reason [WebApi] gives: this surface asks
 * for no credential, so the loopback binding is the whole of what keeps a
 * caller on another machine out, and a host taken from a setting is one typo
 * away from removing all of it.
 *
 * The core need not be up for this to start. Nothing is called until a request
 * arrives, and the calling library outlives a core that is not there yet — so
 * which of the two is started first is nobody's business to arrange.
 */
fun main() {
    val port = requiredSetting(PORT_SETTING).toIntOrNull()
        ?: stop("$PORT_SETTING must be a port number")
    val core = CatalogClient(requiredSetting(CORE_ADDRESS_SETTING))
    val server = WebApi(core, LOOPBACK, port)

    println("the web API is answering on ${server.start()}$API_PATH")

    // Nothing more for this thread to do. The process runs until it is asked to
    // stop, and lets go of the port and the core on its way out.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.close()
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
