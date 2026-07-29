package io.nook.core.catalog

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.nook.contract.AGENT_HEADER
import io.nook.contract.Actor
import io.nook.contract.OperationCatalog
import io.nook.contract.SUBJECT_HEADER
import io.nook.contract.answer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The address a machine uses to reach itself, which nothing outside it can
 * route to — and the only address the core is ever started on.
 */
const val LOOPBACK: String = "127.0.0.1"

/**
 * The core's answering side: one address that every call goes to, with the
 * operation named inside the request.
 *
 * One address rather than one per operation, because that is the only layout in
 * which a request naming an operation nobody defined comes back as a refusal
 * naming it, instead of as the web server's own empty answer for an address
 * nobody defined. For the same reason the reply names its own ending and every
 * reply carries the same numeric status: a number that has to say both "the
 * item is not there" and "the address is not there" decides neither.
 *
 * Nothing here asks a caller for a credential, and that is unchanged: only the
 * machine the core runs on can reach it, and that stays the whole of its
 * protection. Who a call is for arrives beside the request in two headers of
 * Nook's own, put there by an adapter that has already checked a token — no token
 * of a caller's ever reaches here, the protocol's own security rules forbidding
 * one being handed to anything behind a surface.
 *
 * [host] is fixed by whoever builds this and is not a setting the connection
 * offers — see the core's entry point, where binding to the loopback address is
 * the whole of what keeps a caller elsewhere out.
 */
class CatalogServer(catalog: OperationCatalog, private val host: String, port: Int) : AutoCloseable {

    private val engine = embeddedServer(CIO, host = host, port = port) { catalogRoute(catalog) }

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

private fun Application.catalogRoute(catalog: OperationCatalog) {
    routing {
        post("/") {
            call.respondText(replyFor(call, catalog), ContentType.Application.Json)
        }
    }
}

/**
 * The request, run against the core's own catalog, for whoever the adapter that
 * sent it says the call is for.
 *
 * The binding happens before the request is read and whatever the operation
 * turns out to be: a read records nobody but is still made for somebody, and an
 * adapter says who its call is for whatever the call does. The two names are taken
 * exactly as they arrived — nothing trimmed, lowercased or filled in — because
 * a name altered here is a row crediting somebody the token never named.
 *
 * What a request means is decided in the contract library and nowhere here:
 * this end of the connection contributes an address, an engine and a binding,
 * and the app behind the web UI contributes the same three around the same
 * function. Neither holds a rule about what a request contains, which is
 * what makes the two adapters unable to reach different verdicts.
 *
 * The store's work goes to threads that are allowed to sit and wait, kept off
 * the small pool the server answers on: that work waits on the database, and a
 * call held there would otherwise hold an unrelated one behind it.
 *
 * Bytes that could not be read as text at all are handed on as no contents,
 * which is a reply this already has words for — and a shorter path than a
 * second way of saying the same thing.
 */
private suspend fun replyFor(call: ApplicationCall, catalog: OperationCatalog): String {
    val actor = Actor(call.request.header(SUBJECT_HEADER), call.request.header(AGENT_HEADER))
    val request = try {
        call.receiveText()
    } catch (abandoned: CancellationException) {
        throw abandoned
    } catch (unreadable: Exception) {
        ""
    }
    return withContext(Dispatchers.IO) { catalog.answer(request, actor) }
}
