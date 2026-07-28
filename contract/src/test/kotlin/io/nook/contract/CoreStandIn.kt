package io.nook.contract

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket

/** The address a machine uses to reach itself, and the only one anything here listens on. */
const val LOOPBACK: String = "127.0.0.1"

/**
 * A core that answers [body] under [status] to everything, for as long as
 * [check] runs.
 *
 * No core is built and nothing is stored: the reply is written out by hand and
 * served by the runtime's own web server, which is the only way to say "a core
 * a version ahead" or "a core that broke" without having one.
 */
fun coreAnswering(body: String, status: Int = 200, check: (CatalogClient) -> Unit) {
    val server = HttpServer.create(InetSocketAddress(LOOPBACK, 0), 0)
    server.createContext("/") { call ->
        call.requestBody.use { it.readAllBytes() }
        val answer = body.toByteArray()
        call.sendResponseHeaders(status, answer.size.toLong())
        call.responseBody.use { it.write(answer) }
    }
    server.start()
    try {
        CatalogClient("http://$LOOPBACK:${server.address.port}").use(check)
    } finally {
        server.stop(0)
    }
}

/** A caller pointed at a port nothing is listening on, for as long as [check] runs. */
fun noCoreAt(check: (CatalogClient) -> Unit) {
    val nobodyThere = ServerSocket(0).use { it.localPort }
    CatalogClient("http://$LOOPBACK:$nobodyThere").use(check)
}
