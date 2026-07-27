package io.nook.core

import io.nook.core.catalog.CatalogServer
import io.nook.core.catalog.CoreCatalog
import io.nook.core.catalog.LOOPBACK
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import org.jetbrains.exposed.v1.jdbc.Database

/** The port the core answers the connection on. */
const val PORT_SETTING: String = "NOOK_CORE_PORT"

/** Where the structure store is, as a JDBC URL. */
const val DATABASE_SETTING: String = "NOOK_DATABASE_URL"

/**
 * The core service as a program: the store it owns, and the connection its two
 * adapters reach it by.
 *
 * Two settings come from outside, and neither has a default. A default port
 * would have this listening somewhere nobody chose, and a default store would
 * have it writing somewhere nobody chose — so a missing setting stops the
 * program and says which one, rather than starting on a guess.
 *
 * The host is not among them. Binding to the loopback address is the whole of
 * what keeps a caller on another machine out of a connection that asks for no
 * credential, and a host taken from a setting is one typo away from removing
 * all of it.
 */
fun main() {
    val port = requiredSetting(PORT_SETTING).toIntOrNull()
        ?: stop("$PORT_SETTING must be a port number")
    val server = CatalogServer(CoreCatalog(Database.connect(requiredSetting(DATABASE_SETTING))), LOOPBACK, port)

    println("the core is answering on ${server.start()}")

    // Nothing more for this thread to do. The process runs until it is asked to
    // stop, and lets go of the port on its way out.
    Runtime.getRuntime().addShutdownHook(Thread(server::close))
    CountDownLatch(1).await()
}

private fun requiredSetting(name: String): String =
    System.getenv(name) ?: stop("$name is not set, and there is no default for it")

private fun stop(why: String): Nothing {
    System.err.println(why)
    exitProcess(1)
}
