package io.nook.web

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.nook.contract.OperationCatalog
import io.nook.contract.answer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The address a machine uses to reach itself, which nothing outside it can
 * route to — and the only address this app is ever started on.
 */
const val LOOPBACK: String = "127.0.0.1"

/** The one address every operation is served at, and the only one this app defines. */
const val API_PATH: String = "/api"

/**
 * The app the web UI reaches Nook through: one address, with the operation and
 * the project named inside the request.
 *
 * Who calls this decides what it owes. The caller is the web UI arriving in
 * milestone 4 — served by this same app, from the root address — and not a
 * third party integrating against Nook: humans drive Nook through the UI and
 * agents drive it through MCP, so nobody outside this repo is written against
 * what is served here. That is why a read the UI turns out to need is an
 * ordinary operation added to the catalog rather than a break in a public
 * contract — see `docs/01-interface-contracts.md`, which owns this.
 *
 * It serves the core's own shape rather than a second one of its own, which is
 * why there is nothing here that reads a request or decides a verdict. What it
 * contributes is an address, an engine, a binding, and the calling library
 * underneath — and the same shared function the core mounts does the rest. So a
 * request written once is accepted at both doors and comes back saying the same
 * thing, structurally rather than by two programs agreeing to.
 *
 * Everything else this app answers at is the web server's own reply, carrying
 * no error of Nook's. The root is deliberately among them: it is where the web
 * UI arrives later, and nothing about an address says which operation a call is
 * for.
 *
 * [host] is fixed by whoever builds this and is not a setting the app offers —
 * see the entry point, where binding to the loopback address is the whole of
 * what keeps a caller elsewhere out.
 */
class WebApi(catalog: OperationCatalog, private val host: String, port: Int) : AutoCloseable {

    private val engine = embeddedServer(CIO, host = host, port = port) { apiRoute(catalog) }

    /**
     * Where this is listening, readable once [start] has returned. A port left
     * at zero is chosen by the machine, and this is where that choice shows up.
     */
    val address: String by lazy {
        "http://$host:${runBlocking { engine.engine.resolvedConnectors().first().port }}"
    }

    /** Starts listening, and hands back the address it settled on. */
    fun start(): String {
        engine.start(wait = false)
        return address
    }

    override fun close() {
        engine.stop(gracePeriodMillis = 0, timeoutMillis = 0)
    }
}

private fun Application.apiRoute(catalog: OperationCatalog) {
    routing {
        post(API_PATH) {
            call.respondText(replyFor(call, catalog), ContentType.Application.Json)
        }
    }
}

/**
 * The request, run against the catalog this app was built with — which is the
 * calling library, so running it is a call across the connection to the core.
 *
 * That call goes to threads that are allowed to sit and wait, kept off the
 * small pool the server answers on: it waits on another program, and a call
 * held there would otherwise hold an unrelated one behind it.
 *
 * Bytes that could not be read as text at all are handed on as no contents,
 * which is a reply the shared function already has words for.
 */
private suspend fun replyFor(call: ApplicationCall, catalog: OperationCatalog): String {
    val request = try {
        call.receiveText()
    } catch (abandoned: CancellationException) {
        throw abandoned
    } catch (unreadable: Exception) {
        ""
    }
    return withContext(Dispatchers.IO) { catalog.answer(request) }
}
