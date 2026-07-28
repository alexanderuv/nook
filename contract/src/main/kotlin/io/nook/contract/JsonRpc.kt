package io.nook.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The wire, which is not Nook's.
 *
 * Every call the catalog serves travels as a JSON-RPC 2.0 request object and
 * comes back as a JSON-RPC 2.0 response object, on the core's own connection
 * and on the surface a person's program reaches. Nook defines the operations
 * and what their arguments hold; the shape a call travels in is a published
 * specification, so a caller reaches Nook with a client that already exists
 * instead of one written for Nook.
 *
 * Two optional parts of the specification are not served: several calls in one
 * request, and a call expecting no reply. Every call names one operation and
 * gets one reply, which is why an envelope carrying no [RpcRequest.id] is one
 * this does not accept rather than one answered silently.
 */
public const val JSON_RPC_VERSION: String = "2.0"

/**
 * The numbers a reply's error travels under.
 *
 * The first five are the specification's own and cover the request itself. The
 * three below them are Nook's domain refusals, and sit in the range the
 * specification reserves for a server's own errors — all but the fourth, which
 * has no number of Nook's because the standard already has one for it: a
 * refusal of the arguments is a refusal of the arguments, whether this side
 * could not read them or the core turned them down.
 *
 * A caller reads which domain failure it was off [REASON] rather than off these
 * numbers, so nobody has to match integers against a table.
 */
public object RpcCode {

    /** Contents that are not JSON at all. */
    public const val PARSE_ERROR: Int = -32700

    /** Contents that are JSON, and are not a request. */
    public const val INVALID_REQUEST: Int = -32600

    /** An operation nobody defined. */
    public const val METHOD_NOT_FOUND: Int = -32601

    /** Anything wrong with the arguments, whoever found it. */
    public const val INVALID_PARAMS: Int = -32602

    /** A call that produced no verdict. It says no more than that, deliberately. */
    public const val INTERNAL_ERROR: Int = -32603

    /** Something the call named was not there. */
    public const val NOT_FOUND: Int = -32001

    /** Something the call asked for is already taken. */
    public const val CONFLICT: Int = -32002

    /** The call would have made a loop. */
    public const val CYCLE: Int = -32003

    // -32005 is spoken for: a document edit whose expected version did not
    // match. No operation in this catalog can produce one, so there is no case
    // for it here — the document layer adds its own when it has one to raise.
}

/** Where a failure's own name lives in [RpcError.data], so reading it needs no guessing at a number. */
public const val REASON: String = "reason"

/**
 * The number [this] refusal travels under.
 *
 * Three of the four have a number of Nook's. The fourth is the standard's own
 * "invalid params", which is what a failed validation *is* — so a caller that
 * reads only numbers still learns the actionable thing, and the name it lost
 * rides in [REASON].
 */
public val ErrorCode.rpcCode: Int
    get() = when (this) {
        ErrorCode.VALIDATION_FAILED -> RpcCode.INVALID_PARAMS
        ErrorCode.NOT_FOUND -> RpcCode.NOT_FOUND
        ErrorCode.CONFLICT -> RpcCode.CONFLICT
        ErrorCode.CYCLE -> RpcCode.CYCLE
    }

/** What a reply carries when the call failed: the standard's three fields, and nothing else. */
@Serializable
public data class RpcError(
    public val code: Int,
    public val message: String,
    public val data: JsonObject? = null,
)

/**
 * A refusal the core made, as the error object it travels in: its name under
 * [REASON], and the details it already carries alongside.
 */
public fun StructuredError.asRpcError(): RpcError = RpcError(
    code = code.rpcCode,
    message = message,
    data = buildJsonObject {
        put(REASON, code.label)
        details?.forEach { (name, said) -> put(name, said) }
    },
)

/**
 * The refusal [this] carries, or nothing at all where it carries none.
 *
 * An error naming no reason this build knows is not a refusal: nothing about
 * the call is for its caller to correct, and reading it as one would have an
 * adapter tell an agent to fix arguments that were never the problem.
 */
public fun RpcError.asStructuredError(): StructuredError? {
    val carried = data ?: return null
    val reason = carried.text(REASON) ?: return null
    val named = ErrorCode.entries.firstOrNull { it.label == reason } ?: return null
    val details = carried.keys.minus(REASON).mapNotNull { name -> carried.text(name)?.let { name to it } }
    return StructuredError(named, message, details.toMap().ifEmpty { null })
}

/**
 * One call: the operation by name, its arguments, and the value the reply hands
 * back so a caller can pair the two.
 *
 * [params] carries the project a project-scoped operation acts inside alongside
 * that operation's own arguments — no operation defines a field of that name,
 * so the two cannot collide.
 */
@Serializable(with = RpcRequestSerializer::class)
public data class RpcRequest(
    public val method: String,
    public val params: JsonObject = JsonObject(emptyMap()),
    public val id: JsonElement,
)

/**
 * One reply: exactly one of a result and an error, and the id its call carried.
 *
 * "Exactly one" is why this is a choice rather than two fields that could both
 * be filled or both be empty — a reply saying nothing about how the call ended
 * is not one this connection produces.
 */
@Serializable(with = RpcReplySerializer::class)
public sealed interface RpcReply {

    /** The id of the call this answers, or nothing where the request was too broken to carry one. */
    public val id: JsonElement

    /** The operation ran. [result] is its entity or listing, and nothing at all for the two deletes. */
    public data class Answered(override val id: JsonElement, public val result: JsonElement?) : RpcReply

    /** The call failed, and [error] says how. */
    public data class Failed(override val id: JsonElement, public val error: RpcError) : RpcReply
}

public object RpcRequestSerializer : KSerializer<RpcRequest> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("io.nook.contract.RpcRequest") {
        element<String>(VERSION)
        element<String>(METHOD)
        element<JsonObject>(PARAMS, isOptional = true)
        element<JsonElement>(ID)
    }

    private val defined: Set<String> = descriptor.fieldNames().toSet()

    override fun serialize(encoder: Encoder, value: RpcRequest) {
        encoder.jsonOut().encodeJsonElement(
            buildJsonObject {
                put(VERSION, JSON_RPC_VERSION)
                put(METHOD, value.method)
                put(PARAMS, value.params)
                put(ID, value.id)
            },
        )
    }

    override fun deserialize(decoder: Decoder): RpcRequest {
        val envelope = decoder.jsonIn().decodeJsonElement().asEnvelope("a request").also { it.refuseUndefined(defined) }
        envelope.requireVersion()
        val method = envelope.text(METHOD)
            ?: throw SerializationException("a request names the operation it is for in \"method\"")
        return RpcRequest(
            method = method,
            params = when (val supplied = envelope[PARAMS]) {
                null -> JsonObject(emptyMap())
                else -> supplied as? JsonObject
                    ?: throw SerializationException("\"params\" is a set of named fields, and $supplied is not")
            },
            id = envelope[ID]?.takeIf { it.isIdentifier() }
                ?: throw SerializationException(
                    "every call here is answered, so a request carries an \"id\": text or a number",
                ),
        )
    }
}

public object RpcReplySerializer : KSerializer<RpcReply> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("io.nook.contract.RpcReply") {
        element<String>(VERSION)
        element<JsonElement>(RESULT, isOptional = true)
        element<RpcError>(ERROR, isOptional = true)
        element<JsonElement>(ID)
    }

    private val defined: Set<String> = descriptor.fieldNames().toSet()

    override fun serialize(encoder: Encoder, value: RpcReply) {
        val output = encoder.jsonOut()
        output.encodeJsonElement(
            buildJsonObject {
                put(VERSION, JSON_RPC_VERSION)
                when (value) {
                    // A result is written even where the operation produced
                    // none: a delete succeeded, and a reply that carried
                    // nothing at all would be one a caller has to guess about.
                    is RpcReply.Answered -> put(RESULT, value.result ?: JsonNull)
                    is RpcReply.Failed -> put(ERROR, output.json.encodeToJsonElement(RpcError.serializer(), value.error))
                }
                put(ID, value.id)
            },
        )
    }

    override fun deserialize(decoder: Decoder): RpcReply {
        val input = decoder.jsonIn()
        val envelope = input.decodeJsonElement().asEnvelope("a reply").also { it.refuseUndefined(defined) }
        envelope.requireVersion()
        val id = envelope[ID] ?: throw SerializationException("a reply carries the \"id\" of the call it answers")
        val failure = envelope[ERROR]
        return when {
            failure != null && envelope.containsKey(RESULT) ->
                throw SerializationException("a reply carries a result or an error, and this carries both")

            failure != null -> RpcReply.Failed(id, input.json.decodeFromJsonElement(RpcError.serializer(), failure))

            envelope.containsKey(RESULT) -> RpcReply.Answered(id, envelope[RESULT]?.takeIf { it != JsonNull })

            else -> throw SerializationException("a reply carries a result or an error, and this carries neither")
        }
    }
}

private const val VERSION = "jsonrpc"

private const val METHOD = "method"

private const val PARAMS = "params"

private const val ID = "id"

private const val RESULT = "result"

private const val ERROR = "error"

/** The field of `params` naming the project a project-scoped operation acts inside. */
internal const val PROJECT = "project"

/**
 * The id an envelope carries, read off whatever arrived rather than off a
 * request that could be built from it — so a reply to something that was not a
 * request can still be paired with the call that sent it, which is what the
 * specification asks for and the only thing left to salvage.
 */
internal fun JsonElement.callIdentifier(): JsonElement =
    (this as? JsonObject)?.get(ID)?.takeIf { it.isIdentifier() } ?: JsonNull

/** What [name] holds, where it holds text, and nothing at all otherwise. */
private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** An id is text or a number; nothing else pairs a reply with the call that asked for it. */
private fun JsonElement.isIdentifier(): Boolean = this is JsonPrimitive && this != JsonNull

private fun JsonElement.asEnvelope(what: String): JsonObject =
    this as? JsonObject ?: throw SerializationException("$what is a set of named fields, and $this is not")

private fun JsonObject.requireVersion() {
    val said = text(VERSION)
    if (said != JSON_RPC_VERSION) {
        throw SerializationException("this connection speaks JSON-RPC $JSON_RPC_VERSION, and this names $said")
    }
}

private fun JsonObject.refuseUndefined(defined: Set<String>) {
    val undefined = keys - defined
    if (undefined.isNotEmpty()) {
        throw SerializationException(
            "this envelope defines no field named ${undefined.sorted().joinToString { "\"$it\"" }}",
        )
    }
}

private fun Encoder.jsonOut(): JsonEncoder =
    this as? JsonEncoder ?: throw SerializationException("this shape crosses as JSON alone")

private fun Decoder.jsonIn(): JsonDecoder =
    this as? JsonDecoder ?: throw SerializationException("this shape crosses as JSON alone")
