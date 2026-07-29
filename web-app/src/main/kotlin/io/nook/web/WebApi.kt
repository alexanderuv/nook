package io.nook.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.nook.contract.Actor
import io.nook.contract.BEARER_CHALLENGE
import io.nook.contract.BearerTokens
import io.nook.contract.NO_VALID_TOKEN
import io.nook.contract.OperationCatalog
import io.nook.contract.WWW_AUTHENTICATE
import io.nook.contract.answer
import io.nook.contract.catalogJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
 * A call arrives here presenting a bearer token and is turned away without one.
 * [tokens] is what that token is checked against, and it is built before this
 * is: a door started with nothing to check tokens against must stop rather than
 * serve, and building the reading afterwards would be one ordering away from
 * serving anyway.
 *
 * No acting agent is ever recorded through this door. A person works here
 * directly, so the field holds nothing rather than a repeat of their own name.
 *
 * [host] is fixed by whoever builds this and is not a setting the app offers —
 * see the entry point, where binding to the loopback address is what keeps a
 * caller elsewhere off an address a token travels to in the clear.
 */
class WebApi(
    catalog: OperationCatalog,
    tokens: BearerTokens,
    private val host: String,
    port: Int,
) : AutoCloseable {

    private val engine = embeddedServer(CIO, host = host, port = port) { apiRoute(catalog, tokens) }

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

private fun Application.apiRoute(catalog: OperationCatalog, tokens: BearerTokens) {
    routing {
        post(API_PATH) {
            val subject = tokens.subjectPresenting(call.request.headers[AUTHORIZATION])
            if (subject == null) {
                call.turnAway()
                return@post
            }
            call.respondText(replyFor(call, catalog, Actor(subject)), ContentType.Application.Json)
        }
    }
}

/** The header a caller presents its token in, as its own specification names it. */
private const val AUTHORIZATION = "Authorization"

/**
 * A call presenting no valid bearer token, refused.
 *
 * The refusal is written here rather than taken from either of Ktor's own token
 * plugins: both fix the challenge at the word `Bearer` and a realm, where the
 * bearer-token standard defines a fuller one that says the token was what was
 * wrong. What a caller has to do differs between the two kinds of refusal — fix
 * its configuration, or fix its request — so the two must not read alike.
 *
 * It carries none of the four domain reasons, because none of them applies:
 * nothing about the call itself is wrong, and nothing in it is the caller's to
 * correct.
 */
private suspend fun ApplicationCall.turnAway() {
    response.header(WWW_AUTHENTICATE, BEARER_CHALLENGE)
    respondText(
        catalogJson.encodeToString(JsonObject.serializer(), buildJsonObject { put("error", NO_VALID_TOKEN) }),
        ContentType.Application.Json,
        HttpStatusCode.Unauthorized,
    )
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
private suspend fun replyFor(call: ApplicationCall, catalog: OperationCatalog, actor: Actor): String {
    val request = try {
        call.receiveText()
    } catch (abandoned: CancellationException) {
        throw abandoned
    } catch (unreadable: Exception) {
        ""
    }
    return withContext(Dispatchers.IO) { catalog.answer(request, actor) }
}
