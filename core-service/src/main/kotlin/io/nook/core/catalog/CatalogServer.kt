package io.nook.core.catalog

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.nook.contract.CatalogReply
import io.nook.contract.CatalogRequest
import io.nook.contract.ErrorCode
import io.nook.contract.OperationCatalog
import io.nook.contract.StructuredError
import io.nook.contract.StructuredErrorException
import io.nook.contract.catalogJson
import io.nook.contract.perform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

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
            call.respondText(
                catalogJson.encodeToString(replyFor(call, catalog)),
                ContentType.Application.Json,
            )
        }
    }
}

/**
 * Reads the request and runs it inside one attempt.
 *
 * Which line the reading sits on is the whole of this function's difficulty.
 * Read outside the attempt — the arrangement a handler falls into by default —
 * contents that cannot be read fail before anything maps them, and arrive as
 * the web server's own answer where a refusal was required. Nothing about the
 * code looks wrong when it is wrong, which is why there is a test that sends
 * unreadable contents.
 *
 * The store's work goes to threads that are allowed to sit and wait, kept off
 * the small pool the server answers on: that work waits on the database, and a
 * call held there would otherwise hold an unrelated one behind it.
 *
 * Everything the core refuses passes through with its own code, message, and
 * details. Everything else that fails is a fault, carrying no code at all —
 * there is nothing for a caller to fix, so it must not read as though there
 * were.
 */
private suspend fun replyFor(call: ApplicationCall, catalog: OperationCatalog): CatalogReply =
    try {
        val request = catalogJson.decodeFromString<CatalogRequest>(call.receiveText())
        CatalogReply.Answer(withContext(Dispatchers.IO) { catalog.perform(request) })
    } catch (refused: StructuredErrorException) {
        CatalogReply.Refusal(refused.error)
    } catch (unreadable: SerializationException) {
        CatalogReply.Refusal(
            StructuredError(
                ErrorCode.VALIDATION_FAILED,
                unreadable.message ?: "this request's contents could not be read",
            ),
        )
    } catch (abandoned: CancellationException) {
        throw abandoned
    } catch (broken: Exception) {
        CatalogReply.Fault("something inside the core failed: $broken")
    }
