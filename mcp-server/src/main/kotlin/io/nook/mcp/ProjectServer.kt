package io.nook.mcp

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator
import io.modelcontextprotocol.spec.McpSchema
import io.nook.contract.OperationCatalog
import io.nook.contract.Project
import jakarta.servlet.Servlet
import jakarta.servlet.http.HttpServletRequest
import java.util.concurrent.atomic.AtomicBoolean

/** What the protocol calls this server, and what it calls the revision of Nook behind it. */
private const val SERVER_NAME = "nook"

/**
 * The protocol asks every server for a version alongside its name. Nook has no
 * release numbering of its own yet, and inventing one here would be a number
 * nobody maintains.
 */
private const val SERVER_VERSION = "1"

/**
 * The library's transport turns away any address that does not end with the one
 * endpoint it was told about. Behind something that has already decided which
 * project a request is for, that check is a second opinion on routing already
 * settled, and the only thing it can do is disagree — so it is told an ending
 * every address has, and the routing stays in one place.
 */
private const val WHEREVER_IT_IS_MOUNTED = ""

/**
 * Turns away anything a web page sent, and serves everything else.
 *
 * A page open in a browser on this machine reaches the loopback address as
 * readily as an agent does, and this server asks for no credential — so the
 * binding alone would let a page nobody trusts drive an agent's tools. Every
 * request a browser makes carries where the page came from; nothing is allowed
 * to come from a page, and a request carrying no such origin — which is every
 * request an agent's client makes — is served.
 *
 * No host is listed either, which leaves the host unchecked: this is reached at
 * whatever port it was told to listen on, and a list of hosts would be a second
 * copy of that setting, wrong the first time the port changes.
 */
private val NOT_FROM_A_PAGE: ServerTransportSecurityValidator =
    DefaultServerTransportSecurityValidator.builder().build()

/**
 * One project's protocol server: everything a connection opened at that
 * project's address gets.
 *
 * There is one of these per project rather than one for every project, because
 * what a connection is told on opening is a single piece of text held on the
 * server — so a shared server can say something true of every project, or one
 * project's own words, never both.
 *
 * The project's **id** is what every call is made with, never the words the
 * address carried: a handle is freed when its project is deleted and can later
 * be given to a different project, and an agent holding the words would begin
 * working in that other project without anyone saying so.
 */
internal class ProjectServer(catalog: OperationCatalog, project: Project) : AutoCloseable {

    /** The id every call this server makes is made with, and the one it is held under. */
    val projectId: String = project.id.toString()

    /**
     * Whether a call has come back saying this project is no longer there.
     *
     * Set from inside a call and read from outside one, because that is the
     * shape of the thing: the news arrives on a call that has already failed,
     * and what it costs — the whole server, and every connection on it — is too
     * much to spend while an answer is still being written.
     */
    private val gone = AtomicBoolean(false)

    private val transport = HttpServletStreamableServerTransportProvider.builder()
        .mcpEndpoint(WHEREVER_IT_IS_MOUNTED)
        .securityValidator(NOT_FROM_A_PAGE)
        // The one thing the library takes for reading an arriving request, and
        // the whole of what carries the person a call is for to the tool that
        // runs it. The token itself is not among what it carries and never
        // could be: it was spent at the gate, and handing one to anything
        // behind the surface is what the protocol's own security rules forbid.
        .contextExtractor(::whoTheGateRead)
        .build()

    private val server = McpServer.sync(transport)
        .serverInfo(SERVER_NAME, SERVER_VERSION)
        // Tools, and nothing else an agent can act through. The library adds its
        // own logging channel to whatever it is handed, on a line that runs
        // after this one; nothing of Nook's is reachable through it.
        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
        .instructions(announcing(project))
        .tools(toolsFor(catalog, projectId) { gone.set(true) })
        .build()

    /** Whether this project turned out not to exist any more, which is the end of every connection here. */
    val isGone: Boolean get() = gone.get()

    /** What answers requests for this project, once something has decided they are its. */
    val answering: Servlet get() = transport

    /**
     * Stops serving every connection held against this project. A connected
     * client's next call fails and its session is over, which is what there is
     * to do for an agent whose project no longer exists.
     */
    override fun close() {
        server.close()
    }
}

/**
 * The person the gate read off this request's token, carried to wherever a tool
 * runs.
 *
 * Only the person: a connection's requests each carry their own token and each
 * may name a different person, while the agent is announced once when the
 * connection opens and the library holds it against the session, where a tool
 * reads it from the same exchange this reaches it by.
 */
private fun whoTheGateRead(request: HttpServletRequest): McpTransportContext =
    McpTransportContext.create(
        buildMap { request.getAttribute(SUBJECT_ATTRIBUTE)?.let { put(SUBJECT_ATTRIBUTE, it) } },
    )

/**
 * What every connection to a project is told when it opens: which project it is
 * in, by all four of the things that name it, and what that project is for.
 *
 * It rides here because a client's configuration is not something an agent gets
 * to read, so the address alone would leave an agent unable to say where it is —
 * and there is no tool that would tell it, deliberately.
 *
 * It is written once, when the first connection to a project opens, and every
 * later connection to that project is told the same words. They cannot go stale:
 * the four things it names are fixed when a project is created and no operation
 * alters them, and anything created afterwards carries a different id, which is
 * a different server with an announcement of its own.
 */
private fun announcing(project: Project): String = buildString {
    append("This connection works in the Nook project ${project.name} ")
    append("(handle ${project.slug}, id ${project.id}).")
    project.description?.let { append(" $it") }
}
