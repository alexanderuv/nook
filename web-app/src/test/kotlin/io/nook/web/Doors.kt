package io.nook.web

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
import io.nook.contract.ALEX
import io.nook.contract.Actor
import io.nook.contract.BearerTokens
import io.nook.contract.CatalogClient
import io.nook.contract.DEFAULT_WAIT_LIMIT
import io.nook.contract.OperationCatalog
import io.nook.contract.RpcReply
import io.nook.contract.RpcReplySerializer
import io.nook.contract.SUBJECT_HEADER
import io.nook.contract.THE_SECRET
import io.nook.contract.answer
import io.nook.contract.catalogJson
import io.nook.contract.presenting
import io.nook.contract.tokenFor
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The core's own connection, built the way the core builds it: an address, an
 * engine, and the shared answering function over a catalog.
 *
 * The real one lives beside the store, which this module may not reach in any
 * source set — so what stands in for it here is what is left of it once the
 * store is taken away, which is exactly the three things above. That is what
 * makes "the same request, sent to both doors" a fair comparison rather than a
 * comparison with a second implementation.
 *
 * [port] may be fixed by the caller rather than chosen by the machine, because
 * coming back at the *same* address is what a check about a core that went away
 * and returned is about.
 */
class CoreConnection(private val catalog: OperationCatalog, private val port: Int = 0) : AutoCloseable {

    private var engine: EmbeddedCore? = null

    /** Where this is listening, once [start] has returned. */
    var address: String = "http://$LOOPBACK:$port"
        private set

    fun start(): String {
        check(engine == null) { "this core is already listening on $address" }
        engine = EmbeddedCore(catalog, port).also { address = it.start() }
        return address
    }

    fun stop() {
        engine?.close()
        engine = null
    }

    override fun close() {
        stop()
    }
}

private class EmbeddedCore(catalog: OperationCatalog, port: Int) : AutoCloseable {

    private val server = embeddedServer(CIO, host = LOOPBACK, port = port) {
        coreRoute(catalog)
    }

    fun start(): String {
        server.start(wait = false)
        return "http://$LOOPBACK:${runBlocking { server.engine.resolvedConnectors().first().port }}"
    }

    override fun close() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
    }
}

private fun Application.coreRoute(catalog: OperationCatalog) {
    routing {
        post("/") { call.answerWith(catalog) }
    }
}

/**
 * Who the call is for arrives beside the request, in the two headers a door
 * sends, and is bound before anything is run — as the real core does it. No
 * credential is asked for here, and the real core asks for none either.
 */
private suspend fun ApplicationCall.answerWith(catalog: OperationCatalog) {
    val request = receiveText()
    val actor = Actor(this.request.header(SUBJECT_HEADER), this.request.header(AGENT_HEADER))
    respondText(
        withContext(Dispatchers.IO) { catalog.answer(request, actor) },
        io.ktor.http.ContentType.Application.Json,
    )
}

/**
 * Both doors over one stand-in core: the core's own connection, and the app in
 * front of it with the calling library between.
 *
 * Every check in this module goes through here, because what this surface is
 * for is being the same contract as the connection behind it — and a check that
 * stood up only the app would have nothing to say that against.
 */
class BothDoors(
    val core: StandInCore = StandInCore(),
    waitLimit: kotlin.time.Duration = DEFAULT_WAIT_LIMIT,
    corePort: Int = 0,
) : AutoCloseable {

    private val connection = CoreConnection(core, corePort)

    /** Where the core's own connection answers. */
    val coreAddress: String = connection.start()

    private val caller = CatalogClient(coreAddress, waitLimit)

    private val app = WebApi(caller, BearerTokens(THE_SECRET), LOOPBACK, 0)

    /** Where the app serves the eleven operations. */
    val apiAddress: String = app.start() + API_PATH

    /** Where the app is, without the address the operations are at. */
    val appAddress: String = app.address

    /** What each door answered to [body], for the checks that compare the two. */
    fun bothAnswers(body: String): Pair<RpcReply, RpcReply> =
        replyFrom(coreAddress, body) to replyFrom(apiAddress, body)

    /** What the app answered to [body]. */
    fun answer(body: String): RpcReply = replyFrom(apiAddress, body)

    /** Stops the core, leaving the app and its caller exactly as they are. */
    fun stopCore(): Unit = connection.stop()

    /** Starts the core again, at the address it had. */
    fun startCore() {
        connection.start()
    }

    override fun close() {
        app.close()
        caller.close()
        connection.close()
    }
}

/** What a request came back as: the number it arrived under, what it said, and its headers. */
data class Answer(val status: Int, val said: String, val headers: Map<String, List<String>> = emptyMap()) {

    /** Header names arrive under whatever casing the server wrote them in, and are matched without it. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/**
 * One call, written out as text the way a program that is not this one writes
 * it: the operation by name, the project alongside its arguments, and an id for
 * the reply to hand back.
 */
fun rawCall(method: String, params: String = "{}", id: String = "1"): String =
    """{"jsonrpc":"2.0","method":"$method","params":$params,"id":$id}"""

/**
 * [body], sent to [address] exactly as written, presenting a valid token.
 *
 * [presenting] is what goes in the `Authorization` header — a valid token by
 * default, so that every check carried over from before the gate is driven
 * through it unchanged, and whatever a check about the gate itself wants
 * otherwise.
 */
fun sentTo(
    address: String,
    body: String,
    method: String = "POST",
    from: String? = null,
    presenting: String? = presenting(tokenFor(ALEX)),
    naming: Actor? = null,
): Answer {
    val request = HttpRequest.newBuilder(URI.create(address))
        .timeout(PATIENCE)
        .header("Content-Type", "application/json")
        .apply { from?.let { header("Origin", it) } }
        .apply { presenting?.let { header("Authorization", it) } }
        .apply { naming?.subject?.let { header(SUBJECT_HEADER, it) } }
        .apply { naming?.agent?.let { header(AGENT_HEADER, it) } }
        .method(method, HttpRequest.BodyPublishers.ofString(body))
        .build()
    val sent = HttpClient.newBuilder().connectTimeout(PATIENCE).build()
        .send(request, HttpResponse.BodyHandlers.ofString())
    return Answer(sent.statusCode(), sent.body(), sent.headers().map())
}

/**
 * The reply [address] gave to [body], read as a reply.
 *
 * [naming] is who the call is for, as a door tells the core beside the request.
 * Only a check reaching the core directly supplies it — there it is standing in
 * for a door, and a door says who its call is for. The app takes the person
 * from the token alone and reads no such header, which is the whole of why a
 * caller cannot name somebody else.
 */
fun replyFrom(address: String, body: String, naming: Actor? = null): RpcReply =
    catalogJson.decodeFromString(RpcReplySerializer, sentTo(address, body, naming = naming).said)

/**
 * What [address] answered to [body], or nothing at all where nothing answered.
 *
 * Every wait here is bounded: its callers ask at addresses nothing may be
 * listening on, and an unroutable one would otherwise hang a check rather than
 * fail it.
 */
fun reachedAt(address: String, body: String, from: String? = null): Answer? =
    runCatching { sentTo(address, body, from = from) }.getOrNull()

/** A port nothing is listening on, for a core that has to be started twice at the same one. */
fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** Long enough that a loaded machine does not trip it, short enough that a dead address fails rather than hangs. */
private val PATIENCE: Duration = Duration.ofSeconds(10)
