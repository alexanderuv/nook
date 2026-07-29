package io.nook.system

import io.nook.contract.ALEX
import io.nook.contract.presenting
import io.nook.contract.tokenFor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** What the MCP server answers a call it could not reach the core to serve. */
private const val CORE_NOT_REACHED = 503

/** What is served where a call was answered at all. */
private const val SERVED = 200

/** The jars a program that talks to a database starts with, by the names they ship under. */
private val PERSISTENCE_JARS = listOf("postgresql", "exposed", "liquibase", "sqlite")

/** Every table the eleven operations write, which is everything a call could leave behind. */
private val WRITTEN_TABLES = listOf("project", "project_item", "release", "item_dependency")

/** How long the run watches the store while both adapters are being called. */
private val WATCHING: Duration = 5.seconds

/** How long the run watches the store with the core stopped, where every look must come back empty. */
private val WATCHING_WITHOUT_THE_CORE: Duration = 2.seconds

/** How often the store is asked who is connected to it. */
private const val BETWEEN_LOOKS_MILLIS = 25L

/**
 * Two things the assembled system is asked that no single program can answer:
 * that a call presenting no token is turned away before the core is reached at
 * all, and that the core is the only program that ever connects to the store.
 *
 * The second is asked twice over, because neither half is enough alone. What an
 * adapter can load is read off its distribution — a program with no driver has
 * nothing to open a connection with — and what the running system does is read
 * off the store's own view of who is connected to it, with the core there and
 * with the core gone.
 */
class GateAndStoreTest {

    @Test
    fun `a call presenting no token is turned away at both adapters, and the store is untouched`() {
        AssembledSystem().use { system ->
            system.startEverything()
            val project = WebApiCaller(system.webApi, presenting(tokenFor(ALEX))).use { person ->
                person.answered(
                    "create_project",
                    buildJsonObject { put("name", "a project nobody without a token may reach") },
                ).jsonObject
            }
            val toolsAt = system.mcpServer + system.toolsPath(project.text("slug").orEmpty())
            val before = rowsInTheStore(system.database.jdbcUrl)

            assertEquals(
                UNAUTHORIZED,
                sentTo(system.webApi, oneCall("list_projects")).status,
                "the web API served a call presenting no token",
            )
            assertEquals(
                UNAUTHORIZED,
                sentTo(toolsAt, AN_OPENING_EXCHANGE).status,
                "the MCP server served a connection presenting no token",
            )
            assertEquals(before, rowsInTheStore(system.database.jdbcUrl), "a refused call left something behind")

            // With the core stopped, a refusal is still a refusal — while a call
            // that does present a token gets as far as needing the core, and
            // says so. That difference is the gate shown to be in front.
            system.core.stop()
            assertEquals(
                UNAUTHORIZED,
                sentTo(system.webApi, oneCall("list_projects")).status,
                "the web API answered a tokenless call differently once the core was gone",
            )
            assertEquals(
                UNAUTHORIZED,
                sentTo(toolsAt, AN_OPENING_EXCHANGE).status,
                "the MCP server answered a tokenless connection differently once the core was gone",
            )

            val presenting = presenting(tokenFor(ALEX))
            val throughTheGate = sentTo(system.webApi, oneCall("list_projects"), presenting)
            assertEquals(SERVED, throughTheGate.status, "a call presenting a token was turned away")
            assertEquals(
                NO_VERDICT,
                throughTheGate.code,
                "a call presenting a token did not get as far as needing the core; it said: ${throughTheGate.said}",
            )
            assertEquals(
                CORE_NOT_REACHED,
                sentTo(toolsAt, AN_OPENING_EXCHANGE, presenting).status,
                "a connection presenting a token did not get as far as needing the core",
            )

            // And with the core back, both are served on the strength of the
            // same token, so the refusals above were about the token alone.
            system.core.start()
            assertEquals(
                SERVED,
                sentTo(system.webApi, oneCall("list_projects"), presenting).status,
                "the web API turned away a call presenting a valid token",
            )
            assertEquals(
                SERVED,
                sentTo(toolsAt, AN_OPENING_EXCHANGE, presenting).status,
                "the MCP server turned away a connection presenting a valid token",
            )
        }
    }

    @Test
    fun `neither adapter is even shipped something it could open a connection with`() {
        listOf(Distribution.MCP, Distribution.WEB).forEach { adapter ->
            assertEquals(
                emptyList(),
                adapter.jars.filter { jar -> PERSISTENCE_JARS.any(jar::startsWith) },
                "$adapter starts with something it could reach a database through",
            )
        }

        // The control. A check that finds nothing anywhere has not been shown to
        // find anything at all, and the core's distribution is where these jars
        // are supposed to be.
        assertTrue(
            Distribution.CORE.jars.any { jar -> PERSISTENCE_JARS.any(jar::startsWith) },
            "the core starts with no database jar either, so this check finds nothing anywhere",
        )
    }

    @Test
    fun `the connections the store reports are there with the core and gone without it`() {
        AssembledSystem().use { system ->
            system.startEverything()
            val store = system.database.jdbcUrl

            WebApiCaller(system.webApi, presenting(tokenFor(ALEX))).use { person ->
                val project = person.answered(
                    "create_project",
                    buildJsonObject { put("name", "a project being asked about throughout") },
                ).jsonObject

                system.connectionFor(project.text("slug").orEmpty(), AN_AGENT, tokenFor(ALEX)).use { agent ->
                    val keepCalling = AtomicBoolean(true)
                    val callers = Executors.newFixedThreadPool(2)
                    try {
                        callers.execute { while (keepCalling.get()) runCatching { person.call("list_projects") } }
                        callers.execute { while (keepCalling.get()) runCatching { agent.callTool("list_items") } }

                        assertTrue(
                            connectionsAppear(store, WATCHING),
                            "the store reported nobody connected to it while all three programs were being called",
                        )

                        system.core.stop()
                        // Both adapters are still up and still being called, so
                        // whatever the store reports now is theirs if it is
                        // anybody's — and it reports nothing.
                        assertTrue(
                            !connectionsAppear(store, WATCHING_WITHOUT_THE_CORE),
                            "the store reported somebody connected to it with the core stopped",
                        )
                    } finally {
                        keepCalling.set(false)
                        callers.shutdownNow()
                        callers.awaitTermination(1, TimeUnit.MINUTES)
                    }
                }
            }
        }
    }

    /**
     * Whether anybody but this run is connected to the store at any moment
     * inside [watching].
     *
     * Looked for rather than counted once, because the core holds no pool: it
     * opens a connection for a transaction and drops it after, so a single look
     * would catch one only by luck.
     */
    private fun connectionsAppear(store: String, watching: Duration): Boolean {
        val giveUpAt = System.nanoTime() + watching.inWholeNanoseconds
        while (System.nanoTime() < giveUpAt) {
            if (connectedBesidesThisRun(store) > 0) return true
            Thread.sleep(BETWEEN_LOOKS_MILLIS)
        }
        return false
    }

    /**
     * What the store's own view of its callers reports, beyond every connection
     * this run itself opened.
     *
     * Named rather than excluded by which backend is asking: a read here opens a
     * connection and closes it, so one from a moment ago can still be there while
     * the next read looks, and counting that would be this run finding itself.
     */
    private fun connectedBesidesThisRun(store: String): Int = rowsFrom(
        store,
        "select count(*) as connected from pg_stat_activity " +
            "where backend_type = 'client backend' and application_name <> ?",
        THIS_RUN,
    ).single()["connected"].let { (it as Number).toInt() }

    private fun rowsInTheStore(store: String): List<Long> = WRITTEN_TABLES.map { table ->
        rowsFrom(store, "select count(*) as held from $table").single()["held"] as Long
    }
}
