package io.nook.system

import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpError
import io.nook.contract.ALEX
import io.nook.contract.presenting
import io.nook.contract.tokenFor
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The loop Nook exists to serve, run by an agent's client over one MCP
 * connection against the real core and a real store — which is what nothing
 * before there were three programs to start could do.
 *
 * The project comes first and comes from the other adapter, because no tool
 * makes one: this server offers a connection the seven operations that act
 * *inside* the project its address names, and creating a project is not one of
 * them. Everything after that is the connection's alone, with nothing arranged
 * for it in between.
 *
 * Who wrote each row is read twice — once off the replies, and once off the
 * database with plain SQL. A reply saying a row records somebody is the program
 * that wrote it answering about itself; the store is the other end, and asking
 * the other end is what an assembled run is for.
 */
class LoopOverMcpTest {

    @Test
    fun `an agent runs the whole loop over one connection, and the store records who it was for`() {
        AssembledSystem().use { system ->
            system.startEverything()

            val project = WebApiCaller(system.webApi, presenting(tokenFor(ALEX))).use { person ->
                person.answered("create_project", buildJsonObject { put("name", "a project for an agent to work in") })
            }.jsonObject
            val projectId = UUID.fromString(project.text("id"))

            system.connectionFor(project.text("slug").orEmpty(), AN_AGENT, tokenFor(ALEX)).use { agent ->
                val release = agent.answered("create_release", mapOf("name" to TheLoop.RELEASE)).jsonObject
                val epic = agent.answered("create_item", mapOf("type" to "epic", "name" to TheLoop.EPIC)).jsonObject
                val first = agent.answered(
                    "create_item",
                    mapOf("type" to "task", "name" to TheLoop.FIRST_TASK, "parentRef" to epic.text("id")),
                ).jsonObject
                val second = agent.answered(
                    "create_item",
                    mapOf("type" to "task", "name" to TheLoop.SECOND_TASK, "parentRef" to epic.text("id")),
                ).jsonObject
                val bug = agent.answered("create_item", mapOf("type" to "bug", "name" to TheLoop.BUG)).jsonObject

                val inRelease = agent.answered(
                    "update_item",
                    mapOf("ref" to epic.text("id"), "releaseRef" to release.text("id")),
                ).jsonObject
                val waiting = agent.answered(
                    "update_item",
                    mapOf("ref" to second.text("id"), "blockedBy" to listOf(first.text("id"))),
                ).jsonObject

                // The one question the loop exists to ask: of the work that
                // holds nothing else, what is unstarted and unblocked?
                val readyToStart = agent.answered(
                    "list_items",
                    mapOf("types" to TheLoop.LEAF_TYPES, "statuses" to listOf(TheLoop.TODO), "heldUp" to false),
                ).jsonArray

                assertEquals(
                    listOf(bug.text("id"), first.text("id")),
                    readyToStart.map { it.jsonObject.text("id") },
                    "the listing did not hold exactly the leaves nothing is holding up, newest first",
                )
                assertEquals(release.text("id"), inRelease.text("releaseId"), "the epic was not put in the release")
                assertEquals(
                    listOf(first.text("id")),
                    waiting.getValue("blockedBy").jsonArray.map { it.jsonPrimitive.content },
                    "the second task was not made to wait on the first",
                )

                noToolMakesAProject(agent)

                // Every reply names the same pair, and it is the person the
                // token named with the agent the connection announced.
                assertEquals(
                    listOf(through(ALEX, AN_AGENT)),
                    listOf(release, epic, first, second, bug, inRelease, waiting).map { it.whoWroteIt() }.distinct(),
                    "a reply named somebody other than the person and the agent this connection was for",
                )

                theStoreAgrees(
                    system.database.jdbcUrl,
                    projectId,
                    waits = UUID.fromString(second.text("id")),
                    on = UUID.fromString(first.text("id")),
                )
            }
        }
    }

    /**
     * The connection is offered the seven operations that act inside a project
     * and nothing else, and asking for one it does not offer is refused naming
     * what was asked for.
     */
    private fun noToolMakesAProject(agent: McpSyncClient) {
        assertEquals(
            emptyList(),
            agent.listTools().tools().map { it.name() }.filter { it.endsWith("_project") || it == "list_projects" },
            "a connection was offered a tool that acts on projects rather than inside one",
        )

        val refused = assertFailsWith<McpError> {
            agent.callTool("create_project", mapOf("name" to "a project an agent tried to make"))
        }
        assertTrue(
            refused.toString().contains("create_project"),
            "the refusal did not name the tool that was asked for; it said: $refused",
        )
    }

    /** What the store holds, read with plain SQL rather than asked of the programs that wrote it. */
    private fun theStoreAgrees(store: String, project: UUID, waits: UUID, on: UUID) {
        val items = whoWroteEachRowOf(store, "project_item", project)
        val releases = whoWroteEachRowOf(store, "release", project)
        assertEquals(4, items.size, "the store does not hold the four items the loop made: ${items.keys}")
        assertEquals(1, releases.size, "the store does not hold the release the loop made: ${releases.keys}")
        assertEquals(
            listOf(through(ALEX, AN_AGENT)),
            (items.values + releases.values).distinct(),
            "the store recorded somebody other than the person and the agent the connection was for",
        )
        assertEquals(listOf(on), blockersOf(store, waits), "the store holds no edge for the task that waits")

        // The project itself was made by a person working directly, through the
        // other adapter, so it records them and no agent at all.
        val (wroteTheProject, owner) = projectRow(store, project)
        assertEquals(
            byThemselves(ALEX),
            wroteTheProject,
            "the project row does not record the person who made it, acting alone",
        )
        assertEquals(ALEX, owner, "the project is not owned by the person whose token made it")
    }
}
