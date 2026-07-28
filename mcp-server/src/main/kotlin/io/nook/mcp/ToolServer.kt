package io.nook.mcp

import jakarta.servlet.http.HttpServlet
import org.eclipse.jetty.ee11.servlet.ServletContextHandler
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector

/**
 * The address a machine uses to reach itself, which nothing outside it can
 * route to — and the only address this server is ever started on.
 */
const val LOOPBACK: String = "127.0.0.1"

/** Every address a connection can be opened at sits under this one. */
internal const val TOOLS_PATH = "/mcp"

/**
 * The agent's front door as a running server: a web container, bound to one
 * address, handing everything under `/mcp/` to [answering].
 *
 * [host] is fixed by whoever builds this rather than taken from a setting —
 * binding to the loopback address is the whole of what keeps a caller on
 * another machine out of a connection that asks for no credential, and a host
 * taken from a setting is one typo away from removing all of it.
 */
internal class ToolServer(answering: HttpServlet, private val host: String, port: Int) : AutoCloseable {

    private val jetty = Server()

    private val connector = ServerConnector(jetty).also {
        it.host = host
        it.port = port
        jetty.addConnector(it)
    }

    init {
        jetty.handler = ServletContextHandler().apply {
            contextPath = "/"
            addServlet(answering, "$TOOLS_PATH/*")
        }
    }

    /**
     * Where this is listening, readable once [start] has returned. A port left
     * at zero is chosen by the machine, and this is where that choice shows up.
     */
    val address: String get() = "http://$host:${connector.localPort}"

    /** Starts listening, and hands back the address it settled on. */
    fun start(): String {
        jetty.start()
        return address
    }

    override fun close() {
        jetty.stop()
    }
}
