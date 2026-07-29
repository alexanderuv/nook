package io.nook.mcp

import io.modelcontextprotocol.spec.HttpHeaders
import io.nook.contract.Actor
import io.nook.contract.BearerTokens
import io.nook.contract.MAX_ACTOR_LENGTH
import io.nook.contract.BEARER_CHALLENGE
import io.nook.contract.NO_VALID_TOKEN
import io.nook.contract.WWW_AUTHENTICATE
import io.nook.contract.catalogJson
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where the gate leaves the person it read, for whatever runs behind it. */
internal const val SUBJECT_ATTRIBUTE: String = "io.nook.mcp.subject"

/**
 * Where it leaves the agent, on the one request that carries a name to leave.
 *
 * Only a connection being opened announces one. Every later request of that
 * connection is answered by the protocol library, which holds the name against
 * the session — so this is read by the one call the dispatcher makes for itself
 * and by nothing else.
 */
internal const val AGENT_ATTRIBUTE: String = "io.nook.mcp.agent"

/** The header a caller presents its token in, as its own specification names it. */
private const val AUTHORIZATION = "Authorization"

/** The name a client gives for itself, where the opening exchange carries it. */
private const val CLIENT_INFO = "clientInfo"

private const val PARAMS = "params"

private const val NAME = "name"

/**
 * The gate every request to the agent surface passes before any of the protocol
 * runs: a call presenting no valid bearer token is turned away here, and one
 * that does carries the person it named to whatever runs next.
 *
 * It sits in front of [behind] rather than inside the protocol library because
 * that is what puts the opening exchange itself behind it — a connection cannot
 * be opened, and its tools cannot be listed, without a token. Nothing of the
 * protocol has run when a call is refused, and the core has not been asked
 * anything either: the question of which project an address names is asked by
 * the dispatcher, which is behind this.
 *
 * Every request of a connection passes here, not only the first. The long-lived
 * transport makes `GET` and `DELETE` requests of its own, and a gate that let
 * those through would be one a caller could hold a connection open through
 * after its token stopped holding up.
 *
 * The client's own name is checked here too, and that costs the one thing this
 * would otherwise not do: the name arrives inside the body of the opening
 * request, a body can be read once, and the protocol library's transport reads
 * it for itself. So the body is read here and handed on re-readable.
 */
internal class Gate(private val tokens: BearerTokens, private val behind: HttpServlet) : HttpServlet() {

    override fun service(request: HttpServletRequest, response: HttpServletResponse) {
        val subject = tokens.subjectPresenting(request.getHeader(AUTHORIZATION))
        if (subject == null) {
            response.setHeader(WWW_AUTHENTICATE, BEARER_CHALLENGE)
            response.refusing(HttpServletResponse.SC_UNAUTHORIZED, NO_VALID_TOKEN)
            return
        }
        request.setAttribute(SUBJECT_ATTRIBUTE, subject)

        // Only a connection being opened carries a name to check, and only a
        // request carrying a body has anything to re-read. Everything else goes
        // straight on, its body untouched.
        if (!request.opensAConnection()) {
            behind.service(request, response)
            return
        }
        val opening = request.inputStream.readAllBytes()
        val named = clientNameIn(opening.toString(Charsets.UTF_8))
        if (named != null && named.length > MAX_ACTOR_LENGTH) {
            response.refusing(HttpServletResponse.SC_BAD_REQUEST, tooLong(named))
            return
        }
        named?.takeIf { it.isNotEmpty() }?.let { request.setAttribute(AGENT_ATTRIBUTE, it) }
        behind.service(ReadableAgain(request, opening), response)
    }
}

/**
 * Who this request is for, as the gate in front of it read them.
 *
 * A client that gives no name for itself and one that names itself with an
 * empty string arrive identically and mean the same thing: no agent acted. One
 * rule covers both, which is why the empty name never reaches the core.
 */
internal fun HttpServletRequest.actor(): Actor =
    Actor(getAttribute(SUBJECT_ATTRIBUTE) as? String, getAttribute(AGENT_ATTRIBUTE) as? String)

/**
 * Whether this request is a connection being opened rather than one continuing.
 *
 * The same test the dispatcher makes, for the same reason: a request carrying a
 * session belongs to a connection whose opening exchange has already been read,
 * and its body is the protocol's business alone.
 */
private fun HttpServletRequest.opensAConnection(): Boolean =
    method.equals("POST", ignoreCase = true) && getHeader(HttpHeaders.MCP_SESSION_ID) == null

/**
 * The name a client gives for itself in [opening], or nothing where it gives
 * none — which is also what an opening exchange this cannot read comes back as.
 *
 * A body that is not an opening exchange, or not readable at all, is handed on
 * rather than refused here: what a request means is the protocol library's to
 * decide, and a second opinion on it could only disagree.
 */
private fun clientNameIn(opening: String): String? = try {
    catalogJson.parseToJsonElement(opening)
        .let { it as? JsonObject }
        ?.get(PARAMS)?.jsonObject
        ?.get(CLIENT_INFO)?.jsonObject
        ?.get(NAME)?.jsonPrimitive
        ?.takeIf { it.isString }
        ?.content
} catch (unreadable: SerializationException) {
    null
} catch (notWhereItLooked: IllegalArgumentException) {
    null
}

/**
 * What a client naming itself with more than the store can hold is told.
 *
 * The name is recorded on every row that connection writes, so a name no column
 * could hold is not one to shorten silently — a shortened one would credit
 * every row to a client that does not exist.
 */
private fun tooLong(named: String): String =
    "this client names itself with ${named.length} characters, and $MAX_ACTOR_LENGTH is the most Nook records"

/**
 * The opening request with its body put back, so that whatever reads it next
 * reads what the gate read.
 *
 * A servlet request's body is a stream and a stream is spent once. Reading it
 * at the gate and handing the request straight on would leave the protocol
 * library reading nothing, and the connection failing for a reason nobody can
 * see.
 */
private class ReadableAgain(request: HttpServletRequest, private val body: ByteArray) :
    HttpServletRequestWrapper(request) {

    override fun getInputStream(): ServletInputStream {
        val bytes = ByteArrayInputStream(body)
        return object : ServletInputStream() {
            override fun read(): Int = bytes.read()
            override fun isFinished(): Boolean = bytes.available() == 0
            override fun isReady(): Boolean = true
            override fun setReadListener(listener: ReadListener) {
                // Nothing arrives later: the whole body is already here.
            }
        }
    }

    override fun getReader(): BufferedReader =
        BufferedReader(InputStreamReader(inputStream, characterEncoding ?: Charsets.UTF_8.name()))
}
