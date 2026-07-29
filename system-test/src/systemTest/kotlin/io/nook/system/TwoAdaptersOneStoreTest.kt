package io.nook.system

import io.nook.contract.ALEX
import io.nook.contract.presenting
import io.nook.contract.tokenFor
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** How many times each of these runs, which is what makes a race one rather than a coincidence. */
private const val ROUNDS = 100

/**
 * Both adapters writing to one store at once, which is the first thing three
 * programs over one database make it possible to ask.
 *
 * Neither question is about a rule. What decides them is the store: two rows
 * asking for one handle are arbitrated by the database's own unique constraint,
 * and a caller that walks away mid-write is decided by the transaction the write
 * path already opens. Both were claims until there were two adapters and one
 * database to run them against.
 */
class TwoAdaptersOneStoreTest {

    @Test
    fun `two adapters given the same name at the same moment leave two items with different handles`() {
        AssembledSystem().use { system ->
            system.startEverything()

            WebApiCaller(system.webApi, presenting(tokenFor(ALEX))).use { person ->
                val project = person.answered(
                    "create_project",
                    buildJsonObject { put("name", "a project two callers write to at once") },
                ).jsonObject
                val here = project.text("id").orEmpty()

                system.connectionFor(project.text("slug").orEmpty(), AN_AGENT, tokenFor(ALEX)).use { agent ->
                    val bothAtOnce = Executors.newFixedThreadPool(2)
                    try {
                        repeat(ROUNDS) { round ->
                            val name = "the same name, round $round"
                            val (byAgent, byPerson) = bothAtOnce.together(
                                { agent.answered("create_item", mapOf("type" to "task", "name" to name)).jsonObject },
                                {
                                    person.inside(here, "create_item") {
                                        put("type", "task")
                                        put("name", name)
                                    }
                                },
                            )

                            assertNotEquals(
                                byAgent.text("slug"),
                                byPerson.text("slug"),
                                "round $round left two items sharing one handle",
                            )
                        }
                    } finally {
                        bothAtOnce.shutdownNow()
                    }
                }

                val handles = rowsFrom(
                    system.database.jdbcUrl,
                    "select slug from project_item where project_id = ?",
                    UUID.fromString(here),
                ).map { it["slug"] as String }
                assertEquals(ROUNDS * 2, handles.size, "the store does not hold two items per round")
                assertEquals(handles.size, handles.distinct().size, "the store holds two items under one handle")
            }
        }
    }

    @Test
    fun `a caller that walks away mid-write leaves the item with both its edges, or with neither`() {
        AssembledSystem().use { system ->
            system.core.start()
            system.web.start()

            WebApiCaller(system.webApi, presenting(tokenFor(ALEX))).use { person ->
                val project = person.answered(
                    "create_project",
                    buildJsonObject { put("name", "a project whose callers keep leaving") },
                ).jsonObject
                val here = project.text("id").orEmpty()

                val waitsOn = listOf("the first thing it waits on", "the second thing it waits on").map { named ->
                    person.inside(here, "create_item") {
                        put("type", "task")
                        put("name", named)
                    }.text("id").orEmpty()
                }

                val walkedAwayFrom = mutableSetOf<UUID>()
                val written = mutableMapOf<UUID, String>()
                repeat(ROUNDS) { round ->
                    val item = person.inside(here, "create_item") {
                        put("type", "task")
                        put("name", "round $round")
                    }
                    val id = UUID.fromString(item.text("id"))
                    written[id] = "round $round, written"

                    val leaving = runCatching {
                        person.call(
                            "update_item",
                            buildJsonObject {
                                put("project", here)
                                put("ref", item.text("id"))
                                put("name", "round $round, written")
                                putJsonArray("blockedBy") { waitsOn.forEach { add(it) } }
                            },
                            // The wait climbs across the rounds, so that some
                            // callers are certainly gone before the core has
                            // finished and some certainly are not. One short wait
                            // for all of them would have a slow machine abandon
                            // every round, and "never half-written" is only worth
                            // asking where something was written at all.
                            patience = Duration.ofMillis(round + 1L),
                        )
                    }
                    if (leaving.exceptionOrNull() is HttpTimeoutException) walkedAwayFrom += id
                }

                val store = system.database.jdbcUrl
                everyItemIsWholeOrUntouched(store, written, waitsOn.map(UUID::fromString).toSet())

                // A caller that gave up before its request was even on the wire
                // abandoned nothing. What has to have happened at least once is
                // that the caller left and the core finished anyway — which is
                // the only arrangement in which a half-written row was possible.
                assertTrue(
                    walkedAwayFrom.any { item -> nameIn(store, item) == written[item] },
                    "no caller was gone while the core was still writing, so nothing was abandoned mid-write",
                )
            }
        }
    }

    /**
     * Every item the abandoned rounds left behind carries its new name and both
     * its edges, or carries neither — a row holding one of the two would be a
     * write the store let half through.
     */
    private fun everyItemIsWholeOrUntouched(store: String, written: Map<UUID, String>, waitsOn: Set<UUID>) {
        written.forEach { (item, nameOnceWritten) ->
            val name = nameIn(store, item) ?: fail("an item the run created is no longer in the store at all")
            val waiting = blockersOf(store, item).toSet()

            assertEquals(
                name == nameOnceWritten,
                waiting == waitsOn,
                "an abandoned write left the item half-written: name=\"$name\", waiting on $waiting",
            )
        }
    }

    /**
     * [first] and [second] run at the same moment, and both come back with a
     * verdict — which is the thing being asked, since a call that gave up
     * waiting would say nothing about how the store settled the race.
     */
    private fun <T> ExecutorService.together(first: () -> T, second: () -> T): Pair<T, T> {
        val together = CountDownLatch(1)
        val one = submit<T> {
            together.await()
            first()
        }
        val other = submit<T> {
            together.await()
            second()
        }
        together.countDown()
        return verdictOf(one, "the first of two calls made at once") to
            verdictOf(other, "the second of two calls made at once")
    }

    /**
     * What [pending] came back with, bounded well above anything these calls
     * cost so that a wedged one fails rather than hanging the run.
     *
     * Giving up waiting and coming back refused are told apart, because they are
     * opposite news: the first is the thing this check exists to rule out, and
     * the second is a verdict that simply was not the expected one. Reporting
     * both the same way would have a refusal read as a call that never ended.
     */
    private fun <T> verdictOf(pending: Future<T>, what: String): T = try {
        pending.get(1, TimeUnit.MINUTES)
    } catch (neverEnded: TimeoutException) {
        fail("$what reached no verdict: $neverEnded")
    } catch (cameBackWrong: ExecutionException) {
        fail("$what came back, and not with what was expected: ${cameBackWrong.cause}")
    }

    /** What the store holds as [item]'s name, and nothing at all where it holds no such row. */
    private fun nameIn(store: String, item: UUID): String? =
        rowsFrom(store, "select name from project_item where id = ?", item).singleOrNull()?.get("name") as String?
}
