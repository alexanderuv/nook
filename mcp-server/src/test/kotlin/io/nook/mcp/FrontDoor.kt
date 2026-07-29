package io.nook.mcp

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.HttpHeaders
import io.modelcontextprotocol.spec.McpSchema
import io.nook.contract.ALEX
import io.nook.contract.AN_AGENT
import io.nook.contract.BearerTokens
import io.nook.contract.RpcError
import io.nook.contract.THE_SECRET
import io.nook.contract.catalogJson
import io.nook.contract.presenting
import io.nook.contract.tokenFor
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** What the server answered a request made without a protocol client: its status, its words, and its headers. */
data class Answer(val status: Int, val said: String, val headers: Map<String, List<String>> = emptyMap()) {

    /** Header names arrive under whatever casing the server wrote them in, and are matched without it. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/**
 * The token a client presents, on every request it makes.
 *
 * It is held rather than fixed because a token travels with each request and
 * not with the connection — so a check about one connection recording two
 * people in turn swaps this between calls, which is the whole distinction the
 * two identities rest on.
 */
class Presenting(@Volatile var token: String? = tokenFor(ALEX))

/**
 * The whole front door over a stand-in core, running: the gate, the dispatcher
 * behind it, the web container both are mounted in, and whatever connections a
 * test opens against it.
 *
 * Every check in this module goes through here rather than reaching for a
 * protocol server directly, because which project a connection is for is decided
 * at this door and nowhere else — a test that skipped it would be testing an
 * arrangement that never runs. The gate is here for the same reason: it is what
 * every request passes now, and a check that went around it would be measuring
 * a door that no longer exists.
 */
class FrontDoor(core: StandInCore) : AutoCloseable {

    private val dispatcher = Dispatcher(core)

    private val host = ToolServer(Gate(BearerTokens(THE_SECRET), dispatcher), LOOPBACK, 0)

    private val opened = mutableListOf<McpSyncClient>()

    /** Where this is listening. The port was chosen by the machine, so tests can run side by side. */
    val address: String = host.start()

    /** A client connected at [projectRef], its opening exchange completed. */
    fun connectedAt(
        projectRef: String,
        presenting: Presenting = Presenting(),
        named: String? = AN_AGENT,
    ): McpSyncClient = clientAt(projectRef, presenting, named).also { it.initialize() }

    /** A client aimed at [projectRef] that has not opened a connection yet. */
    fun clientAt(
        projectRef: String,
        presenting: Presenting = Presenting(),
        named: String? = AN_AGENT,
    ): McpSyncClient {
        val transport = HttpClientStreamableHttpTransport.builder(address)
            .endpoint("$TOOLS_PATH/$projectRef")
            .httpRequestCustomizer { request, _, _, _, _ ->
                presenting.token?.let { request.header(AUTHORIZATION, presenting(it)) }
            }
            .build()
        return McpClient.sync(transport)
            .apply { named?.let { clientInfo(McpSchema.Implementation.builder(it, "1").build()) } }
            .build()
            .also { opened += it }
    }

    /**
     * The opening exchange at [path], made without a client, so that what the
     * server answered can be read as it was written rather than as some client
     * chose to report it.
     */
    fun openingAt(path: String, origin: String? = null, presenting: String? = presenting(tokenFor(ALEX))): Answer =
        openedAt("$address$path", origin, presenting) ?: error("nothing answered at $address$path")

    /** The opening exchange at [path], made by a client naming itself [named]. */
    fun openingNamed(path: String, named: String): Answer =
        postedAt("$address$path", openingFrom(named), presenting = presenting(tokenFor(ALEX)))
            ?: error("nothing answered at $address$path")

    /**
     * One tool call at [path] with no handshake behind it, so that what a
     * connection that never opened is served can be read.
     */
    fun callingWithoutOpeningAt(path: String, presenting: String? = presenting(tokenFor(ALEX))): Answer =
        postedAt("$address$path", A_TOOL_CALL, presenting = presenting)
            ?: error("nothing answered at $address$path")

    override fun close() {
        // A connection that was refused has nothing to close politely, and
        // teardown is not where that gets reported.
        opened.forEach { runCatching { it.close() } }
        host.close()
        dispatcher.close()
    }
}

/** One ordinary tool call, which the checks about the gate use to show a connection working. */
fun McpSyncClient.getItem(): McpSchema.CallToolResult =
    callTool(McpSchema.CallToolRequest.builder("get_item").arguments(mapOf("ref" to "add-search")).build())

/** What a call came back saying, as one piece of text. */
fun McpSchema.CallToolResult.said(): String =
    content().filterIsInstance<McpSchema.TextContent>().joinToString("\n") { it.text() }

/** What a failed call carried, read back as the contract writes a refusal. */
fun McpSchema.CallToolResult.asRefusal(): RpcError =
    catalogJson.decodeFromString(RpcError.serializer(), said())

/**
 * What an opening exchange at [url] was answered with, or nothing at all where
 * nothing answered.
 *
 * Every wait here is bounded: one of its callers asks at addresses nothing may
 * be listening on, and an unroutable one would otherwise hang the check rather
 * than fail it.
 *
 * [origin] is where a page making this request came from. Only a browser sends
 * it, and only a check about browsers supplies it.
 */
fun openedAt(url: String, origin: String? = null, presenting: String? = presenting(tokenFor(ALEX))): Answer? =
    postedAt(url, OPENING, origin, presenting)

/** [body], posted at [url] the way a protocol client would post it. */
fun postedAt(
    url: String,
    body: String,
    origin: String? = null,
    presenting: String? = presenting(tokenFor(ALEX)),
): Answer? = sentTo(url, "POST", body, origin, presenting)

/**
 * [body], sent to [url] under [method] — for the checks about the `GET` and
 * `DELETE` requests a long-lived connection makes, which pass the same gate
 * every `POST` does.
 */
fun sentTo(
    url: String,
    method: String,
    body: String = "",
    origin: String? = null,
    presenting: String? = presenting(tokenFor(ALEX)),
    session: String? = null,
): Answer? = runCatching {
    HttpClient.newBuilder().connectTimeout(PATIENCE).build().use { plainly ->
        val asked = HttpRequest.newBuilder(URI.create(url))
            .timeout(PATIENCE)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply { origin?.let { header("Origin", it) } }
            .apply { presenting?.let { header(AUTHORIZATION, it) } }
            .apply { session?.let { header(HttpHeaders.MCP_SESSION_ID, it) } }
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build()
        plainly.send(asked, HttpResponse.BodyHandlers.ofString()).let {
            Answer(it.statusCode(), it.body(), it.headers().map())
        }
    }
}.getOrNull()

/** A port nothing is listening on, for a server that has to be started twice at the same one. */
fun freePort(): Int = ServerSocket(0).use { it.localPort }

/**
 * [dispatcher] behind the gate, as the program mounts it — for the checks that
 * stand a server up themselves rather than through [FrontDoor].
 */
internal fun gatedBy(dispatcher: Dispatcher): Gate = Gate(BearerTokens(THE_SECRET), dispatcher)

/** Long enough that a loaded machine does not trip it, short enough that a dead address fails rather than hangs. */
private val PATIENCE: Duration = Duration.ofSeconds(5)

/** The header a caller presents its token in, written here as the checks write it. */
private const val AUTHORIZATION = "Authorization"

/** What a client says first. Written out rather than built, so what the door sees is not a client's idea of it. */
private const val OPENING =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",""" +
        """"capabilities":{},"clientInfo":{"name":"a plain client","version":"1"}}}"""

/** The same opening exchange, from a client naming itself [named]. */
fun openingFrom(named: String): String =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",""" +
        """"capabilities":{},"clientInfo":{"name":"$named","version":"1"}}}"""

/** An opening exchange from a client that gives no name for itself at all. */
const val OPENING_NAMING_NOBODY: String =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",""" +
        """"capabilities":{}}}"""

/** What a client sends once its opening exchange has been answered, before it calls anything. */
const val THE_HANDSHAKE_IS_DONE: String = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

/** A call a client would only make once its handshake had completed. */
const val A_TOOL_CALL: String =
    """{"jsonrpc":"2.0","id":2,"method":"tools/call",""" +
        """"params":{"name":"get_item","arguments":{"ref":"add-search"}}}"""
