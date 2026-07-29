package io.nook.system

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The protocol's own number for a call that reached no verdict at all. */
internal const val NO_VERDICT: Int = -32603

/** What a call presenting no valid bearer token is answered with. */
internal const val UNAUTHORIZED: Int = 401

/** The header a caller presents its token in, as its own specification names it. */
private const val AUTHORIZATION = "Authorization"

/** The call an operation taking no arguments carries. */
internal val NOTHING: JsonObject = JsonObject(emptyMap())

/**
 * What one call came back as: the status it was answered with, and what it
 * said, word for word.
 *
 * A reply is read here as the text that arrived rather than decoded into a shape
 * of Nook's. Decoding it with the same declarations the programs encoded it with
 * would have both ends agree by construction, and an assembled run is the one
 * place that agreement is worth asking about.
 */
internal data class Answer(val status: Int, val said: String) {

    /** The reply, where what arrived was a set of named fields at all. */
    val body: JsonObject? = runCatching { Json.parseToJsonElement(said).jsonObject }.getOrNull()

    /** What the call produced, where it produced anything — the two deletes produce nothing. */
    val result: JsonElement? get() = body?.get("result")?.takeUnless { it == JsonNull }

    /** How the call failed, where it failed. */
    val failure: JsonObject? get() = body?.get("error") as? JsonObject

    /** The number the failure travels under. */
    val code: Int? get() = failure?.get("code")?.jsonPrimitive?.intOrNull
}

/**
 * The web API as a caller reaches it: ordinary requests to the one address, each
 * presenting the token it was built with.
 *
 * [presenting] being nothing sends no `Authorization` header at all, which is
 * what the checks about the gate ask with.
 */
internal class WebApiCaller(private val address: String, private val presenting: String?) : AutoCloseable {

    private val calls = AtomicInteger()

    private val client: HttpClient = HttpClient.newHttpClient()

    /**
     * One call, as the reply came back — a result or a refusal, whichever it
     * carried.
     *
     * [patience] cuts this one call's wait short, for the check about a caller
     * that walks away before its answer arrives. It belongs to the call rather
     * than to the caller because that is how long it lives: every other call the
     * same caller makes waits as long as it takes.
     */
    fun call(operation: String, arguments: JsonObject = NOTHING, patience: Duration? = null): Answer {
        val request = HttpRequest.newBuilder(URI.create(address))
            .header("Content-Type", "application/json")
            .apply { presenting?.let { header(AUTHORIZATION, it) } }
            .apply { patience?.let { timeout(it) } }
            .POST(HttpRequest.BodyPublishers.ofString(oneCall(operation, arguments, calls.incrementAndGet())))
            .build()
        val answered = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Answer(answered.statusCode(), answered.body())
    }

    /** What [operation] produced, where the call was answered at all. */
    fun answered(operation: String, arguments: JsonObject = NOTHING): JsonElement {
        val answer = call(operation, arguments)
        return answer.result ?: fail("$operation was not answered; it said: ${answer.said}")
    }

    override fun close() {
        client.close()
    }
}

/** One call inside [project], which is where the seven project-scoped operations act. */
internal fun WebApiCaller.inside(
    project: String,
    operation: String,
    arguments: JsonObjectBuilder.() -> Unit,
): JsonObject = answered(
    operation,
    buildJsonObject {
        put("project", project)
        arguments()
    },
).jsonObject

/**
 * One call, written out as the standard defines it — the operation by name, its
 * arguments beside the project it acts inside, and an id for the reply to hand
 * back.
 */
internal fun oneCall(operation: String, arguments: JsonObject = NOTHING, id: Int = 1): String = Json.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", operation)
        put("params", arguments)
        put("id", id)
    },
)
