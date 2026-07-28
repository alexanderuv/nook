package io.nook.mcp

import io.nook.contract.CatalogReply
import io.nook.contract.CatalogRequest
import io.nook.contract.StructuredErrorException
import io.nook.contract.catalogJson
import io.nook.contract.perform
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
 * Everything else in this module hands the front door a catalog directly, which
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
 * One request in, one reply out, in the three shapes the connection defines.
 *
 * The catalog decides all three; nothing here adds a verdict of its own, which
 * is what makes this a stand-in for the core rather than a second core.
 */
private class CoreServlet(private val core: StandInCore) : HttpServlet() {

    override fun doPost(request: HttpServletRequest, response: HttpServletResponse) {
        val asked = catalogJson.decodeFromString(CatalogRequest.serializer(), request.reader.readText())
        val reply = try {
            CatalogReply.Answer(core.perform(asked))
        } catch (refused: StructuredErrorException) {
            CatalogReply.Refusal(refused.error)
        } catch (broke: Exception) {
            CatalogReply.Fault(broke.message ?: "something inside the core came apart")
        }
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.writer.write(catalogJson.encodeToString(CatalogReply.serializer(), reply))
    }
}
