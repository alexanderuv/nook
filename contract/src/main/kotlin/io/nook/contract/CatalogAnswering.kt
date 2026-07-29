package io.nook.contract

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What every call that produced no verdict says, whoever it reached and
 * whatever gave way.
 *
 * One sentence for all of them, and a constant rather than a wording each
 * caller writes for itself, because the sameness is the point: a caller can do
 * nothing differently for knowing which part of Nook failed, and a reply that
 * named one would make that part a promise. The distinction survives where it
 * is useful and private — the calling library keeps it for its own recovery.
 */
public const val NO_VERDICT: String = "this call produced no verdict, and nothing about the request was wrong"

/**
 * The whole of what a request means, from the text that arrived to the text
 * that goes back.
 *
 * Both front doors mount this and neither adds to it. The core hands it the
 * catalog over its own store; the app behind the web UI hands it the calling
 * library. So there is one piece of code deciding what a request is,
 * which is what makes "the same request reaches the same verdict at either
 * door" structural rather than a promise two programs keep by discipline.
 *
 * What is here is reading and running, and not a web server: this module
 * defines the shapes every program agrees on, and each program keeps its own
 * address, its own engine, and its own binding.
 *
 * Nothing throws out of here but a caller giving up, so a route can answer with
 * what comes back without arranging for anything else.
 *
 * [actor] is taken here rather than left to each route to bind for itself, so
 * that a route cannot reach the eleven operations without having said who the
 * call is for. Binding happens before the request is so much as read: a request
 * that turns out to be unreadable is still a request made by somebody, and
 * deciding who afterwards would be one ordering away from deciding it never.
 */
public fun OperationCatalog.answer(request: String, actor: Actor): String =
    catalogJson.encodeToString(RpcReplySerializer, forActor(actor).replyTo(request))

/**
 * The four ways a request can be one this cannot act on, then the call itself.
 *
 * The reading sits inside the attempt rather than before it, which is the whole
 * difficulty here: read outside, contents that cannot be read fail before
 * anything maps them and arrive as the web server's own answer where a reply
 * was required. Nothing about the code looks wrong when it is wrong, which is
 * why there is a check that sends unreadable contents.
 */
private fun OperationCatalog.replyTo(request: String): RpcReply {
    val document = try {
        catalogJson.parseToJsonElement(request)
    } catch (unreadable: SerializationException) {
        return failed(JsonNull, RpcCode.PARSE_ERROR, "these contents could not be read as JSON")
    }

    val call = try {
        catalogJson.decodeFromJsonElement(RpcRequestSerializer, document)
    } catch (notARequest: SerializationException) {
        // The id is fished out of whatever did arrive, so a caller pairing
        // replies with calls can still pair this one.
        return failed(document.callIdentifier(), RpcCode.INVALID_REQUEST, notARequest.said())
    }

    val operation = CatalogOperation.fromLabel(call.method)
        ?: return failed(
            call.id,
            RpcCode.METHOD_NOT_FOUND,
            "this connection carries no operation named \"${call.method}\"",
        )

    return try {
        RpcReply.Answered(call.id, wiringOf(operation).runAgainst(this, call.inside(), call.arguments()))
    } catch (refused: StructuredErrorException) {
        RpcReply.Failed(call.id, refused.error.asRpcError())
    } catch (unreadable: SerializationException) {
        RpcReply.Failed(call.id, StructuredError(ErrorCode.VALIDATION_FAILED, unreadable.said()).asRpcError())
    } catch (abandoned: CancellationException) {
        throw abandoned
    } catch (noVerdict: Exception) {
        failed(call.id, RpcCode.INTERNAL_ERROR, NO_VERDICT)
    }
}

/**
 * The project this call acts inside, or nothing where it names none.
 *
 * Whether naming one is right is not decided here: an operation that cannot be
 * invoked without a project and one with nowhere to put a project each refuse
 * their own mistake, in one place, rather than in eleven.
 */
private fun RpcRequest.inside(): String? = when (val named = params[PROJECT]) {
    null -> null
    else -> (named as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw SerializationException("\"$PROJECT\" takes text, and $named is not text")
}

/** Everything the call carries but the project, which is what the operation itself defines. */
private fun RpcRequest.arguments(): JsonObject = JsonObject(params - PROJECT)

private fun failed(id: JsonElement, code: Int, message: String): RpcReply.Failed =
    RpcReply.Failed(id, RpcError(code, message))

/** What this refusal said, or a plain statement of the kind of mistake where it said nothing. */
private fun SerializationException.said(): String = message ?: "this request could not be read"
