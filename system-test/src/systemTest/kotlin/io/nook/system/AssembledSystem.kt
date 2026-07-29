package io.nook.system

import io.nook.contract.THE_SECRET
import io.nook.core.db.RestartablePostgres

/** The address a machine uses to reach itself, and the only one any of these programs binds to. */
internal const val LOOPBACK: String = "127.0.0.1"

/** The one address the web API serves every operation at. */
private const val API_PATH = "/api"

/** Every address a project's connection is opened at sits under this one. */
private const val TOOLS_PATH = "/mcp"

/**
 * The whole of Nook as this run arranges it: a database of the run's own, and
 * the three programs told where to answer and where to find each other.
 *
 * Nothing is started here. Which program comes up first is the caller's, because
 * one check is about exactly that and the rest have no business arranging
 * something the programs are built not to need.
 *
 * Every address is settled before anything starts, so a program stopped and
 * started again comes back where it was and nothing pointed at it has to be
 * rebuilt.
 */
internal class AssembledSystem : AutoCloseable {

    private val corePort = freePort()

    private val mcpPort = freePort()

    private val webPort = freePort()

    /** The store this run owns, and the only one anything here connects to. */
    val database: RestartablePostgres = RestartablePostgres(freePort())

    val core: Program = Program(
        Distribution.CORE,
        mapOf(CORE_PORT to corePort.toString(), DATABASE_URL to database.jdbcUrl),
    )

    val mcp: Program = Program(
        Distribution.MCP,
        mapOf(
            MCP_PORT to mcpPort.toString(),
            CORE_ADDRESS to "http://$LOOPBACK:$corePort",
            TOKEN_SECRET to THE_SECRET,
        ),
    )

    val web: Program = Program(
        Distribution.WEB,
        mapOf(
            WEB_PORT to webPort.toString(),
            CORE_ADDRESS to "http://$LOOPBACK:$corePort",
            TOKEN_SECRET to THE_SECRET,
        ),
    )

    /** The one address every operation of the web API is served at. */
    val webApi: String get() = "http://$LOOPBACK:$webPort$API_PATH"

    /** Where the MCP server answers, under which every project has an address of its own. */
    val mcpServer: String get() = "http://$LOOPBACK:$mcpPort"

    /** The path [project]'s connection is opened at, which is what a client is pointed at. */
    fun toolsPath(project: String): String = "$TOOLS_PATH/$project"

    /** Starts all three, each waited for until it says where it is answering. */
    fun startEverything() {
        core.start()
        mcp.start()
        web.start()
    }

    /**
     * Stops everything, and reports the first thing that would not stop rather
     * than the last — with the rest attached, so a teardown that went wrong in
     * two places says so.
     */
    override fun close() {
        val refused = listOf<AutoCloseable>(web, mcp, core, database)
            .mapNotNull { runCatching(it::close).exceptionOrNull() }
        refused.firstOrNull()?.let { first ->
            refused.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }
}
