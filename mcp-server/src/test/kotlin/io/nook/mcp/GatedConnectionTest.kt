package io.nook.mcp

import io.modelcontextprotocol.spec.HttpHeaders
import io.modelcontextprotocol.spec.McpSchema
import io.nook.contract.ALEX
import io.nook.contract.ANOTHER_SECRET
import io.nook.contract.MAX_ACTOR_LENGTH
import io.nook.contract.NOT_A_TOKEN
import io.nook.contract.WWW_AUTHENTICATE
import io.nook.contract.aProject
import io.nook.contract.presenting
import io.nook.contract.tokenFor
import io.nook.contract.tokenSignedWithNothing
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a call presenting no valid bearer token comes back as. */
private const val UNAUTHORIZED = 401

/** What a client naming itself with more than the store can hold comes back as. */
private const val BAD_REQUEST = 400

private const val SERVED = 200

/**
 * The gate in front of the agent surface: what it turns away, what it lets
 * through, and that nothing of the protocol runs before it.
 *
 * The store being unchanged is the stand-in core recording nothing: a refused
 * call must not reach the core at all, and the one question this adapter asks for
 * itself — which project an address names — is counted apart from tool calls
 * precisely so that "nothing reached the core" can be said without it meaning
 * "the adapter never asked".
 */
class GatedConnectionTest {

    private val project = aProject()

    private fun adapter(check: (StandInCore, RunningAdapter) -> Unit) {
        val core = StandInCore().apply { projects += project }
        RunningAdapter(core).use { check(core, it) }
    }

    private val at get() = "$TOOLS_PATH/${project.slug}"

    /** Every token this adapter must refuse, under the name of what is wrong with it. */
    private val badTokens: Map<String, String> = mapOf(
        "signed with something else" to tokenFor(ALEX, secret = ANOTHER_SECRET),
        "expired an hour ago" to tokenFor(ALEX, until = Instant.now().minus(1, ChronoUnit.HOURS)),
        "issued for another recipient" to tokenFor(ALEX, recipient = "somewhere-else"),
        "signed with nothing at all" to tokenSignedWithNothing(),
        "not a token at all" to NOT_A_TOKEN,
        "carrying no subject" to tokenFor(null),
        "an empty subject" to tokenFor(""),
        "a subject of only spaces" to tokenFor("   "),
        "a subject of 201 characters" to tokenFor("n".repeat(MAX_ACTOR_LENGTH + 1)),
    )

    @Test
    fun `a call presenting no bearer token at all is refused, and nothing of the protocol runs`() {
        adapter { core, adapter ->
            listOf(
                "no Authorization header" to null,
                "a header carrying something that is not a bearer token" to "Something-Else abcdef",
                "a bearer scheme carrying nothing" to "Bearer ",
            ).forEach { (what, header) ->
                val answered = adapter.openingAt(at, presenting = header)

                assertEquals(UNAUTHORIZED, answered.status, what)
                assertTrue(
                    answered.header(WWW_AUTHENTICATE)?.startsWith("Bearer") == true,
                    "$what came back without a challenge saying how to present a token",
                )
                assertNull(answered.header(HttpHeaders.MCP_SESSION_ID), "$what was issued a session")
            }
            assertEquals(emptyList(), core.invocations, "a refused call reached the core")
        }
    }

    @Test
    fun `each token that does not hold up is refused, and none of them reaches the core`() {
        adapter { core, adapter ->
            badTokens.forEach { (what, token) ->
                val answered = adapter.openingAt(at, presenting = presenting(token))

                assertEquals(UNAUTHORIZED, answered.status, what)
                assertTrue(answered.header(WWW_AUTHENTICATE) != null, "$what came back without a challenge")
                assertNull(answered.header(HttpHeaders.MCP_SESSION_ID), "$what was issued a session")
            }
            assertEquals(emptyList(), core.invocations, "a call presenting a token that does not hold up reached the core")
        }
    }

    @Test
    fun `a client that opens a connection without a token gets no session and can list no tool`() {
        adapter { core, adapter ->
            val client = adapter.clientAt(project.slug, Presenting(token = null))

            assertTrue(runCatching { client.initialize() }.isFailure, "a connection opened without a token")
            assertTrue(runCatching { client.listTools() }.isFailure, "tools were listed on a connection never opened")
            assertEquals(emptyList(), core.invocations, "a client with no token reached the core")
        }
    }

    @Test
    fun `a refused call leaves the adapter serving, and the next valid one is served normally`() {
        adapter { _, adapter ->
            assertEquals(UNAUTHORIZED, adapter.openingAt(at, presenting = null).status)

            val client = adapter.connectedAt(project.slug)
            assertTrue(client.listTools().tools().isNotEmpty(), "no tool was offered after a refused call")
            assertEquals(false, client.getItem().isError(), "a tool call was not served after a refused call")
        }
    }

    @Test
    fun `a client naming itself with what the store holds is served, and one character more is refused`() {
        adapter { _, adapter ->
            val asWideAsItGets = "c".repeat(MAX_ACTOR_LENGTH)
            assertEquals(SERVED, adapter.openingNamed(at, asWideAsItGets).status, "a name the store can hold was refused")

            val tooLong = "c".repeat(MAX_ACTOR_LENGTH + 1)
            val refused = adapter.openingNamed(at, tooLong)
            assertEquals(BAD_REQUEST, refused.status, "a name wider than the store can hold was served")
            assertTrue(
                refused.said.contains("${MAX_ACTOR_LENGTH + 1}") && refused.said.contains("$MAX_ACTOR_LENGTH"),
                "the answer did not say the name is too long; it said: ${refused.said}",
            )
        }
    }

    @Test
    fun `an ordinary client opens a connection, lists its tools, and calls one through the gate`() {
        adapter { core, adapter ->
            val client = adapter.connectedAt(project.slug)

            assertEquals(
                declaredTools.map { it.declaration.name() }.toSet(),
                client.listTools().tools().map { it.name() }.toSet(),
            )
            assertEquals(false, client.getItem().isError(), "an ordinary tool call was not served")
            assertEquals(project.id.toString(), core.toolCalls.single().project)
        }
    }
}
