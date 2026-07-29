package io.nook.mcp

import io.nook.contract.AGENT_HEADER
import io.nook.contract.Actor
import io.nook.contract.SUBJECT_HEADER
import io.nook.contract.answer
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.ee11.servlet.ServletContextHandler
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector

/**
 * The stand-in core, reachable the way the real one is: over the connection the
 * calling library speaks.
 *
 * Everything else in this module hands the adapter a catalog directly, which
 * is the right shape for asking what crossed. This one exists for the two
 * questions that only a real connection can answer — whether the program the
 * operator starts actually reaches a core it was told about, and what happens
 * when that core goes away and comes back.
 *
 * [port] is fixed by the caller rather than chosen by the machine, because
 * coming back at the *same* address is the whole point: a connection that
 * recovers only because it was pointed somewhere new proves nothing.
 */
class StandInCoreHost(core: StandInCore, private val port: Int) : AutoCloseable {

    private val answering = CoreServlet(core)

    private var jetty: Server? = null

    val address: String = "http://$LOOPBACK:$port"

    fun start() {
        check(jetty == null) { "this core is already listening on $address" }
        jetty = Server().apply {
            addConnector(
                ServerConnector(this).also {
                    it.host = LOOPBACK
                    it.port = port
                },
            )
            handler = ServletContextHandler().apply {
                contextPath = "/"
                addServlet(answering, "/*")
            }
            start()
        }
    }

    fun stop() {
        jetty?.stop()
        jetty = null
    }

    override fun close() {
        stop()
    }
}

/**
 * One request in, one reply out, decided entirely by the answering side the
 * real core mounts.
 *
 * Nothing here adds a verdict of its own — an address, a container, and the
 * shared function, which is exactly what the core is once its store is taken
 * away. That is what makes this a stand-in for the core rather than a second
 * core.
 *
 * Who a call is for arrives beside the request, in the two headers an adapter
 * sends, and is bound before anything is run — again as the real core does it.
 * Nothing here asks a caller for a credential, and the real core asks for none
 * either.
 */
private class CoreServlet(private val core: StandInCore) : HttpServlet() {

    override fun doPost(request: HttpServletRequest, response: HttpServletResponse) {
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        val actor = Actor(request.getHeader(SUBJECT_HEADER), request.getHeader(AGENT_HEADER))
        response.writer.write(core.answer(request.reader.readText(), actor))
    }
}
