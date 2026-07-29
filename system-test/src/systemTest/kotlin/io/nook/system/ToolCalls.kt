package io.nook.system

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import io.nook.contract.presenting
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** The header a caller presents its token in, as its own specification names it. */
private const val AUTHORIZATION = "Authorization"

/**
 * A connection to the MCP server, opened the way a coding agent's client opens
 * one: at the project's own address, naming itself, and presenting its token on
 * every request it makes.
 *
 * Driven by the protocol library's own client rather than by requests written
 * here. The session handling and the server-sent replies of the transport are
 * the library's business, and a second implementation of them would leave the
 * run measuring this module's reading of the protocol instead of a client's.
 */
internal fun AssembledSystem.connectionFor(project: String, named: String, token: String): McpSyncClient {
    val transport = HttpClientStreamableHttpTransport.builder(mcpServer)
        .endpoint(toolsPath(project))
        .httpRequestCustomizer { request, _, _, _, _ -> request.header(AUTHORIZATION, presenting(token)) }
        .build()
    return McpClient.sync(transport)
        .clientInfo(McpSchema.Implementation.builder(named, "1").build())
        .build()
        .also { it.initialize() }
}

/** One tool call, by name and with the arguments a caller would write. */
internal fun McpSyncClient.callTool(tool: String, arguments: Map<String, Any?> = emptyMap()): McpSchema.CallToolResult =
    callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build())

/** What [tool] produced, where the call was served at all. */
internal fun McpSyncClient.answered(tool: String, arguments: Map<String, Any?> = emptyMap()): JsonElement {
    val called = callTool(tool, arguments)
    if (called.isError() == true) fail("$tool was refused; it said: ${called.said()}")
    return Json.parseToJsonElement(called.said())
}

/** What a call came back saying, as one piece of text. */
internal fun McpSchema.CallToolResult.said(): String =
    content().filterIsInstance<McpSchema.TextContent>().joinToString("\n") { it.text() }
