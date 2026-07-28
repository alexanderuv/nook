package io.nook.mcp

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import io.nook.contract.StructuredError
import io.nook.contract.catalogJson
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** What the server answered a request made without a protocol client: its status, and its words. */
data class Answer(val status: Int, val said: String)

/**
 * The whole front door over a stand-in core, running: the dispatcher, the web
 * container it is mounted in, and whatever connections a test opens against it.
 *
 * Every check in this module goes through here rather than reaching for a
 * protocol server directly, because which project a connection is for is decided
 * at this door and nowhere else — a test that skipped it would be testing an
 * arrangement that never runs.
 */
class FrontDoor(core: StandInCore) : AutoCloseable {

    private val dispatcher = Dispatcher(core)

    private val host = ToolServer(dispatcher, LOOPBACK, 0)

    private val opened = mutableListOf<McpSyncClient>()

    /** Where this is listening. The port was chosen by the machine, so tests can run side by side. */
    val address: String = host.start()

    /** A client connected at [projectRef], its opening exchange completed. */
    fun connectedAt(projectRef: String): McpSyncClient = clientAt(projectRef).also { it.initialize() }

    /** A client aimed at [projectRef] that has not opened a connection yet. */
    fun clientAt(projectRef: String): McpSyncClient =
        McpClient.sync(HttpClientStreamableHttpTransport.builder(address).endpoint("$TOOLS_PATH/$projectRef").build())
            .build()
            .also { opened += it }

    /**
     * The opening exchange at [path], made without a client, so that what the
     * server answered can be read as it was written rather than as some client
     * chose to report it.
     */
    fun openingAt(path: String, origin: String? = null): Answer =
        openedAt("$address$path", origin) ?: error("nothing answered at $address$path")

    /**
     * One tool call at [path] with no handshake behind it, so that what a
     * connection that never opened is served can be read.
     */
    fun callingWithoutOpeningAt(path: String): Answer =
        postedAt("$address$path", A_TOOL_CALL) ?: error("nothing answered at $address$path")

    override fun close() {
        // A connection that was refused has nothing to close politely, and
        // teardown is not where that gets reported.
        opened.forEach { runCatching { it.close() } }
        host.close()
        dispatcher.close()
    }
}

/** What a call came back saying, as one piece of text. */
fun McpSchema.CallToolResult.said(): String =
    content().filterIsInstance<McpSchema.TextContent>().joinToString("\n") { it.text() }

/** What a failed call carried, read back as the contract writes a refusal. */
fun McpSchema.CallToolResult.asRefusal(): StructuredError =
    catalogJson.decodeFromString(StructuredError.serializer(), said())

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
fun openedAt(url: String, origin: String? = null): Answer? = postedAt(url, OPENING, origin)

/** [body], posted at [url] the way a protocol client would post it. */
fun postedAt(url: String, body: String, origin: String? = null): Answer? = runCatching {
    HttpClient.newBuilder().connectTimeout(PATIENCE).build().use { plainly ->
        val asked = HttpRequest.newBuilder(URI.create(url))
            .timeout(PATIENCE)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply { origin?.let { header("Origin", it) } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        plainly.send(asked, HttpResponse.BodyHandlers.ofString()).let { Answer(it.statusCode(), it.body()) }
    }
}.getOrNull()

/** A port nothing is listening on, for a server that has to be started twice at the same one. */
fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** Long enough that a loaded machine does not trip it, short enough that a dead address fails rather than hangs. */
private val PATIENCE: Duration = Duration.ofSeconds(5)

/** What a client says first. Written out rather than built, so what the door sees is not a client's idea of it. */
private const val OPENING =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",""" +
        """"capabilities":{},"clientInfo":{"name":"a plain client","version":"1"}}}"""

/** A call a client would only make once its handshake had completed. */
private const val A_TOOL_CALL =
    """{"jsonrpc":"2.0","id":2,"method":"tools/call",""" +
        """"params":{"name":"get_item","arguments":{"ref":"add-search"}}}"""
