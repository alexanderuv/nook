package io.nook.system

import io.nook.contract.ALEX
import io.nook.contract.presenting
import io.nook.contract.tokenFor
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The same loop, carried out by a person working directly through the web API,
 * with the MCP server not running at all.
 *
 * Two things make it worth running twice. The web API is the only program a
 * project can be created through, so this is the one path that carries the loop
 * end to end unaided; and nobody acts through an agent here, so every row it
 * writes has to record the person its token named and nothing beside them.
 *
 * The MCP server is deliberately left down. An adapter that is not running must
 * be no part of what the other one can do, and the only way to say so is to run
 * one without the other.
 */
class LoopOverWebApiTest {

    @Test
    fun `a person runs the whole loop through the web API alone, and no row records an agent`() {
        AssembledSystem().use { system ->
            system.core.start()
            system.web.start()

            WebApiCaller(system.webApi, presenting(tokenFor(ALEX))).use { person ->
                val project = person.answered(
                    "create_project",
                    buildJsonObject { put("name", "a project nobody needed an agent for") },
                ).jsonObject
                val here = project.text("id").orEmpty()

                val release = person.inside(here, "create_release") { put("name", TheLoop.RELEASE) }
                val epic = person.inside(here, "create_item") {
                    put("type", "epic")
                    put("name", TheLoop.EPIC)
                }
                val first = person.inside(here, "create_item") {
                    put("type", "task")
                    put("name", TheLoop.FIRST_TASK)
                    put("parentRef", epic.text("id"))
                }
                val second = person.inside(here, "create_item") {
                    put("type", "task")
                    put("name", TheLoop.SECOND_TASK)
                    put("parentRef", epic.text("id"))
                }
                val bug = person.inside(here, "create_item") {
                    put("type", "bug")
                    put("name", TheLoop.BUG)
                }

                val inRelease = person.inside(here, "update_item") {
                    put("ref", epic.text("id"))
                    put("releaseRef", release.text("id"))
                }
                val waiting = person.inside(here, "update_item") {
                    put("ref", second.text("id"))
                    putJsonArray("blockedBy") { add(first.text("id")) }
                }

                val readyToStart = person.answered(
                    "list_items",
                    buildJsonObject {
                        put("project", here)
                        putJsonArray("types") { TheLoop.LEAF_TYPES.forEach { add(it) } }
                        putJsonArray("statuses") { add(TheLoop.TODO) }
                        put("heldUp", false)
                    },
                ) as JsonArray

                assertEquals(
                    listOf(bug.text("id"), first.text("id")),
                    readyToStart.map { it.jsonObject.text("id") },
                    "the listing did not hold exactly the leaves nothing is holding up, newest first",
                )
                assertEquals(release.text("id"), inRelease.text("releaseId"), "the epic was not put in the release")
                assertEquals(
                    listOf(byThemselves(ALEX)),
                    listOf(project, release, epic, first, second, bug, inRelease, waiting)
                        .map { it.whoWroteIt() }
                        .distinct(),
                    "a reply recorded an agent, though nobody was working through one",
                )

                theStoreAgrees(
                    system.database.jdbcUrl,
                    UUID.fromString(here),
                    waits = UUID.fromString(second.text("id")),
                    on = UUID.fromString(first.text("id")),
                )
            }
        }
    }

    /** What the store holds, read with plain SQL rather than asked of the program that wrote it. */
    private fun theStoreAgrees(store: String, project: UUID, waits: UUID, on: UUID) {
        val items = whoWroteEachRowOf(store, "project_item", project)
        val releases = whoWroteEachRowOf(store, "release", project)
        assertEquals(4, items.size, "the store does not hold the four items the loop made: ${items.keys}")
        assertEquals(1, releases.size, "the store does not hold the release the loop made: ${releases.keys}")
        assertEquals(listOf(on), blockersOf(store, waits), "the store holds no edge for the task that waits")

        val (wroteTheProject, owner) = projectRow(store, project)
        assertEquals(
            listOf(byThemselves(ALEX)),
            (items.values + releases.values + wroteTheProject).distinct(),
            "the store recorded an agent, though nobody was working through one",
        )
        assertEquals(ALEX, owner, "the project is not owned by the person whose token made it")
    }
}
