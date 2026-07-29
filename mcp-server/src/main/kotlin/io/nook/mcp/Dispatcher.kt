package io.nook.mcp

import io.modelcontextprotocol.spec.HttpHeaders
import io.nook.contract.OperationCatalog
import io.nook.contract.Project
import io.nook.contract.StructuredErrorException
import io.nook.contract.catalogJson
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a request is written in and answered in, whichever way it ends. */
private const val AS_JSON = "application/json"

private const val AS_TEXT = "UTF-8"

/**
 * The adapter: every request arrives here, and what happens to it is decided
 * by the project its address names.
 *
 * A connection opening is where the project is settled. The core is asked
 * whether the address names anything, once, before there is a server for it to
 * reach — so a wrong address is answered when the connection opens rather than discovered
 * again on every call, and an agent whose configuration never took effect is
 * told so by the thing it was trying to connect to.
 *
 * Everything after that is routing: a request that carries a session belongs to
 * a connection whose project was settled when it opened, and goes to the server
 * holding it without the core being asked anything.
 */
internal class Dispatcher(private val catalog: OperationCatalog) : HttpServlet(), AutoCloseable {

    /** One protocol server per project, by the project's id, whatever address reached it. */
    private val servers = ConcurrentHashMap<String, ProjectServer>()

    /**
     * Where each address leads, rewritten every time a connection opens there.
     *
     * An address is not a project: a handle is freed when its project is deleted
     * and can later name a different one. So this says where an address leads
     * *now*, and only a connection opening may write it — a connection already
     * open keeps reaching the server it opened against until that server turns it
     * away, which is the whole of how an agent avoids being moved somewhere it
     * never asked to be.
     */
    private val routes = ConcurrentHashMap<String, ProjectServer>()

    override fun service(request: HttpServletRequest, response: HttpServletResponse) {
        val address = request.pathInfo.orEmpty().removePrefix("/")
        when {
            address.isEmpty() ->
                response.refusing(HttpServletResponse.SC_NOT_FOUND, "no project named in the address")

            request.getHeader(HttpHeaders.MCP_SESSION_ID) != null ->
                continuing(address, request, response)

            else -> opening(address, request, response)
        }
    }

    /**
     * A request on a connection already open: to the server that address leads
     * to, without the core being asked anything.
     *
     * Unless that project turned out to be gone, which a call of its own
     * discovered. Then this is where the news is acted on: the server is closed,
     * ending every connection held against it, and this request — the first
     * after the one that found out — is not served.
     *
     * What it is told says the connection is over and nothing about whether the
     * project exists, because at this point nobody has asked. Opening a fresh
     * connection does ask, and gets the accurate answer; saying it here would be
     * guessing, and guessing wrong every time an address leads nowhere for some
     * reason other than a project having gone.
     */
    private fun continuing(address: String, request: HttpServletRequest, response: HttpServletResponse) {
        val serving = routes[address]
        if (serving == null || serving.isGone) {
            serving?.let(::stopServing)
            response.refusing(HttpServletResponse.SC_NOT_FOUND, "this connection is no longer being served")
            return
        }
        serving.answering.service(request, response)
    }

    /**
     * A connection being opened at [address]: the project settled with the core,
     * and the request handed to that project's server.
     *
     * The two ways this can fail are told apart because they are fixed in
     * different places. A refusal is the core answering — whatever it objects to
     * about the address, no project is going to answer to it, and repeating the
     * address back is what tells a person where to look. Anything else is the
     * core not answering at all, which is not the address's fault and must never
     * be reported as one.
     */
    private fun opening(address: String, request: HttpServletRequest, response: HttpServletResponse) {
        val project = try {
            // Bound like every other call, though this one records nobody: an
            // adapter tells the core who a call is for whatever the call does, and
            // one place where it does not is one place a rule has an exception.
            catalog.forActor(request.actor()).getProject(address)
        } catch (refused: StructuredErrorException) {
            response.refusing(HttpServletResponse.SC_NOT_FOUND, unanswered(address))
            return
        } catch (unreachable: Exception) {
            response.refusing(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "the core could not be reached")
            return
        }
        serverFor(project).also { routes[address] = it }.answering.service(request, response)
    }

    private fun serverFor(project: Project): ProjectServer =
        servers.computeIfAbsent(project.id.toString()) { ProjectServer(catalog, project) }

    /** Lets go of a server for good: nothing leads to it any more, and every connection on it ends. */
    private fun stopServing(server: ProjectServer) {
        servers.remove(server.projectId, server)
        routes.values.removeIf { it == server }
        server.close()
    }

    /** Stops serving every project, and every connection held against one. */
    override fun close() {
        servers.values.forEach { it.close() }
        servers.clear()
        routes.clear()
    }
}

/** What is said about an address nothing answers to: the address itself, so the reader can see the typo. */
private fun unanswered(address: String): String = "no project answers to '$address'"

/**
 * Turns a request down, saying why, in the words that were asked for.
 *
 * The body is written straight onto the response rather than left to the web
 * container's own page for a refusal: that page escapes what it is given into
 * markup, so a placeholder nobody filled in comes back looking like nothing
 * anybody wrote, and the one thing that makes a wrong address diagnosable is
 * lost.
 */
internal fun HttpServletResponse.refusing(code: Int, said: String) {
    status = code
    contentType = AS_JSON
    characterEncoding = AS_TEXT
    writer.write(catalogJson.encodeToString(JsonObject.serializer(), buildJsonObject { put("error", said) }))
    writer.flush()
}
