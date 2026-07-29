package io.nook.system

import io.modelcontextprotocol.spec.McpError
import io.nook.contract.ALEX
import io.nook.contract.presenting
import io.nook.contract.tokenFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The four refusals a caller can act on. A call that reached no verdict carries
 * none of them, and that is the whole distinction: nothing about the request was
 * wrong, so there is nothing for the caller to correct.
 */
private val DOMAIN_REASONS = listOf("validation_failed", "not_found", "conflict", "cycle")

/**
 * A part of the system going away underneath the rest, and coming back.
 *
 * What is being asked is whether anything has to be arranged around it. Neither
 * adapter is restarted, no client is rebuilt, and nothing is told that the core
 * or the store went away — so if a later call is served, it is served because
 * the arrangement already survives this rather than because the run helped.
 *
 * While something is missing, a call must say that it reached no verdict and
 * must not say anything a caller could act on. An agent told its arguments were
 * wrong would go and change them, and they were never the problem.
 */
class GoneAndBackTest {

    @Test
    fun `the core goes away and comes back, and the same caller is served again`() {
        AssembledSystem().use { system ->
            system.startEverything()

            WebApiCaller(system.webApi, presenting(tokenFor(ALEX))).use { person ->
                person.answered(
                    "create_project",
                    buildJsonObject { put("name", "a project made before the core went away") },
                )

                system.core.stop()
                reachedNoVerdict(person.call("list_projects"), "a call made with the core stopped")

                system.core.start()
                assertEquals(
                    1,
                    person.answered("list_projects").jsonArray.size,
                    "the call after the core returned did not come back with what was there before it went away",
                )
            }
        }
    }

    @Test
    fun `the store goes away and comes back, and both adapters serve again`() {
        AssembledSystem().use { system ->
            system.startEverything()

            WebApiCaller(system.webApi, presenting(tokenFor(ALEX))).use { person ->
                val project = person.answered(
                    "create_project",
                    buildJsonObject { put("name", "a project made before the store went away") },
                ).jsonObject

                system.connectionFor(project.text("slug").orEmpty(), AN_AGENT, tokenFor(ALEX)).use { agent ->
                    agent.answered("list_items")

                    system.database.stop()

                    reachedNoVerdict(person.call("list_projects"), "a web API call made with the store stopped")
                    val broke = assertFailsWith<McpError> { agent.callTool("list_items") }
                    assertEquals(
                        NO_VERDICT,
                        broke.jsonRpcError.code(),
                        "a tool call made with the store stopped did not report a breakdown",
                    )
                    assertEquals(
                        emptyList(),
                        DOMAIN_REASONS.filter { broke.toString().contains(it) },
                        "a tool call made with the store stopped told the agent to fix something; it said: $broke",
                    )

                    system.database.start()

                    // The same programs, the same connection, the same caller —
                    // none of them rebuilt, and none of them told anything.
                    person.answered("list_projects")
                    agent.answered("list_items")
                }
            }
        }
    }

    private fun reachedNoVerdict(answer: Answer, what: String) {
        assertEquals(NO_VERDICT, answer.code, "$what did not report a breakdown; it said: ${answer.said}")
        assertEquals(
            emptyList(),
            DOMAIN_REASONS.filter { answer.said.contains(it) },
            "$what told its caller to fix something; it said: ${answer.said}",
        )
    }
}
