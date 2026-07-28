package io.nook.mcp

import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.TypeRef
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import io.nook.contract.Missing
import io.nook.contract.OperationCatalog
import io.nook.contract.ProjectOperation
import io.nook.contract.StructuredError
import io.nook.contract.StructuredErrorException
import io.nook.contract.catalogJson
import io.nook.contract.projectOperations
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One of the catalog's project-scoped operations, offered as a tool: what a
 * client is told about it, and what happens when it is called.
 *
 * Everything a client is told comes from [operation] — its name, its words, its
 * arguments and theirs. This holds no list of tools and no list of arguments,
 * so there is nothing here that could drift from the contract and nothing here
 * that could offer an eighth tool.
 */
internal class DeclaredTool(private val operation: ProjectOperation) {

    val declaration: McpSchema.Tool = McpSchema.Tool
        .builder(operation.name, operation.arguments.asArguments())
        .description(operation.description)
        .build()

    /**
     * Runs this tool's operation inside [project] and turns what came back into
     * one of the three endings a call can have.
     *
     * Two of the three are results, and both are returned from here: an answer
     * carrying what the operation produced, and a refusal carrying the core's
     * own code, message and details for the caller to act on. The third —
     * a breakdown, where no verdict was reached at all — is thrown rather than
     * returned, so that it arrives as a fault of the protocol's rather than as a
     * failed call: there is nothing for a caller to fix, and it must not read as
     * though there were.
     *
     * Arguments this operation cannot read sit with the refusals, because they
     * are the same kind of news: something about the request needs changing.
     * They carry no code of Nook's, since no code of Nook's ran to produce one.
     *
     * One refusal is more than news about this call: the one saying the project
     * itself is not there. The caller is then working somewhere that no longer
     * exists and every call it makes afterwards would fail the same way, so
     * [whenProjectIsGone] is told — and the refusal still goes back, because the
     * agent has to learn what happened from the call it made.
     */
    fun callInside(
        catalog: OperationCatalog,
        project: String,
        request: McpSchema.CallToolRequest,
        whenProjectIsGone: () -> Unit,
    ): McpSchema.CallToolResult =
        try {
            answered(operation.runInside(catalog, project, request.arguments().asJsonObject()))
        } catch (refused: StructuredErrorException) {
            if (Missing.of(refused.error) == Missing.PROJECT) whenProjectIsGone()
            refused(refused.error)
        } catch (unreadable: SerializationException) {
            unreadable(unreadable)
        }
}

/** The seven, declared. Built once, so a shape nothing can be derived from stops the server rather than a call. */
internal val declaredTools: List<DeclaredTool> = projectOperations.map(::DeclaredTool)

internal val protocolJson: McpJsonMapper = McpJsonDefaults.getMapper()

private val namedFields = object : TypeRef<Map<String, Any>>() {}

/**
 * A call's arguments, as the shape behind the tool reads them.
 *
 * They arrive parsed by the protocol library's own reader and are handed on as
 * the text it would write for them, rather than walked value by value: a walk
 * written by hand is a set of cases, and a case nobody wrote drops what a caller
 * supplied without saying so.
 */
private fun Map<String, Any?>?.asJsonObject(): JsonObject =
    catalogJson.parseToJsonElement(protocolJson.writeValueAsString(this ?: emptyMap<String, Any>())) as JsonObject

/**
 * A call that ran, carrying what the operation produced — or carrying nothing
 * at all, which is what a delete produces and is still plainly a success.
 *
 * The result travels as the text the contract writes for it, always; and as
 * structured fields too wherever it is a set of named fields, which is what the
 * protocol's structured form holds. A listing is a sequence rather than a set of
 * named fields, so it travels as its text alone.
 */
private fun answered(result: JsonElement?): McpSchema.CallToolResult {
    val answer = McpSchema.CallToolResult.builder().isError(false)
    if (result != null) {
        val text = catalogJson.encodeToString(JsonElement.serializer(), result)
        answer.addTextContent(text)
        if (result is JsonObject) answer.structuredContent(protocolJson.readValue(text, namedFields))
    }
    return answer.build()
}

/** A call the core turned down, carrying its code, its message and its details exactly as they arrived. */
private fun refused(error: StructuredError): McpSchema.CallToolResult {
    val text = catalogJson.encodeToString(StructuredError.serializer(), error)
    return McpSchema.CallToolResult.builder()
        .isError(true)
        .addTextContent(text)
        .structuredContent(protocolJson.readValue(text, namedFields))
        .build()
}

/**
 * A call whose arguments the operation could not read. It carries no code,
 * because none of Nook's code ran to decide it — what it carries is what was
 * wrong with the arguments, which is what the caller needs.
 */
private fun unreadable(cause: SerializationException): McpSchema.CallToolResult =
    McpSchema.CallToolResult.builder()
        .isError(true)
        .addTextContent(cause.message ?: "these arguments could not be read")
        .build()

/** [declaredTools], as the library takes them: each declaration beside the code that answers it. */
internal fun toolsFor(
    catalog: OperationCatalog,
    project: String,
    whenProjectIsGone: () -> Unit,
): List<McpServerFeatures.SyncToolSpecification> =
    declaredTools.map { tool ->
        McpServerFeatures.SyncToolSpecification(tool.declaration) { _, request ->
            tool.callInside(catalog, project, request, whenProjectIsGone)
        }
    }
