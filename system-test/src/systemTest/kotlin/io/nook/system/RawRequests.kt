package io.nook.system

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The header a caller presents its token in, as its own specification names it. */
private const val AUTHORIZATION = "Authorization"

/** Long enough that a loaded machine does not trip it, short enough that nothing hangs the run. */
private val PATIENCE: Duration = Duration.ofSeconds(30)

/**
 * What a client says first, written out rather than built.
 *
 * The checks about the gate cannot be made through the protocol library's
 * client: it presents whatever token it was built with on every request it
 * makes, and there is no building one that presents none. So the opening
 * exchange is written here, and what the server answers is read as it was
 * written rather than as a client chose to report it.
 */
internal const val AN_OPENING_EXCHANGE: String =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",""" +
        """"capabilities":{},"clientInfo":{"name":"a plain client","version":"1"}}}"""

/** [body], posted to [url] exactly as it stands, presenting [token] only where there is one. */
internal fun sentTo(url: String, body: String, token: String? = null): Answer {
    val request = HttpRequest.newBuilder(URI.create(url))
        .timeout(PATIENCE)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .apply { token?.let { header(AUTHORIZATION, it) } }
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    return HttpClient.newHttpClient().use { plainly ->
        plainly.send(request, HttpResponse.BodyHandlers.ofString()).let { Answer(it.statusCode(), it.body()) }
    }
}
