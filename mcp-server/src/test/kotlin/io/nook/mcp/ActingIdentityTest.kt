package io.nook.mcp

import io.modelcontextprotocol.spec.HttpHeaders
import io.nook.contract.ALEX
import io.nook.contract.AN_AGENT
import io.nook.contract.Actor
import io.nook.contract.JORDAN
import io.nook.contract.aProject
import io.nook.contract.tokenFor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a call presenting no valid bearer token comes back as, whatever method it was made under. */
private const val UNAUTHORIZED = 401

/** The other agent, for the checks about two connections at once. */
private const val ANOTHER_AGENT = "a-slower-agent"

/**
 * What a tool is told about who its call is for: the person from the token this
 * very request presented, and the agent from the name the connection announced
 * when it opened.
 *
 * The two travel differently and that is the whole point of the pair. A
 * connection handed a different person's token on its next request records that
 * person; the agent is fixed for the life of the connection, because that is
 * where the protocol asks for it and the only place it is said.
 */
class ActingIdentityTest {

    private val project = aProject()

    private fun door(check: (StandInCore, FrontDoor) -> Unit) {
        val core = StandInCore().apply { projects += project }
        FrontDoor(core).use { check(core, it) }
    }

    @Test
    fun `one connection whose token changes records each call's own person, with the agent unchanged`() {
        door { core, door ->
            val presenting = Presenting(tokenFor(ALEX))
            val client = door.connectedAt(project.slug, presenting)

            client.getItem()
            presenting.token = tokenFor(JORDAN)
            client.getItem()
            presenting.token = tokenFor(ALEX)
            client.getItem()

            assertEquals(
                listOf(
                    Actor(ALEX, AN_AGENT),
                    Actor(JORDAN, AN_AGENT),
                    Actor(ALEX, AN_AGENT),
                ),
                core.toolCalls.map { it.actor },
            )
        }
    }

    @Test
    fun `two connections working at once never take each other's pair`() {
        door { core, door ->
            val onAlexs = door.connectedAt(project.slug, Presenting(tokenFor(ALEX)), AN_AGENT)
            val onJordans = door.connectedAt(project.id.toString(), Presenting(tokenFor(JORDAN)), ANOTHER_AGENT)

            val each = 100
            val threads = Executors.newFixedThreadPool(8)
            val together = CountDownLatch(1)
            try {
                repeat(each) {
                    threads.execute { together.await(); onAlexs.getItem() }
                    threads.execute { together.await(); onJordans.getItem() }
                }
                together.countDown()
                threads.shutdown()
                assertTrue(threads.awaitTermination(2, TimeUnit.MINUTES), "the two connections did not finish")
            } finally {
                threads.shutdownNow()
            }

            val recorded = core.toolCalls.map { it.actor }
            assertEquals(each * 2, recorded.size, "the calls did not all reach the core")
            assertEquals(
                emptyList(),
                recorded.filterNot { it == Actor(ALEX, AN_AGENT) || it == Actor(JORDAN, ANOTHER_AGENT) },
                "a call recorded a pair neither connection had",
            )
            assertEquals(each, recorded.count { it == Actor(ALEX, AN_AGENT) })
            assertEquals(each, recorded.count { it == Actor(JORDAN, ANOTHER_AGENT) })
        }
    }

    @Test
    fun `a client that names itself with nothing has its calls served, recording no acting agent`() {
        // A client naming itself with an empty string and one giving no name at
        // all arrive identically, which is what makes "no agent acted" one rule
        // rather than two. Both opening exchanges are written out here: the
        // protocol library's own client refuses to be built either way, so a
        // check driven through it could not ask this question at all.
        mapOf(
            "a client naming itself with an empty string" to openingFrom(""),
            "a client giving no name for itself at all" to OPENING_NAMING_NOBODY,
        ).forEach { (what, opening) ->
            door { core, door ->
                val at = "${door.address}$TOOLS_PATH/${project.slug}"
                val opened = postedAt(at, opening)
                assertEquals(200, opened?.status, "$what was not served")

                val session = opened?.header(HttpHeaders.MCP_SESSION_ID)
                assertTrue(session != null, "$what was issued no session")
                sentTo(at, "POST", THE_HANDSHAKE_IS_DONE, session = session)
                sentTo(at, "POST", A_TOOL_CALL, session = session)

                assertEquals(listOf(Actor(ALEX, null)), core.toolCalls.map { it.actor }, what)
            }
        }
    }

    @Test
    fun `the requests a long-lived connection makes for itself are gated like every call it sends`() {
        door { _, door ->
            val opened = door.openingAt("$TOOLS_PATH/${project.slug}")
            val session = opened.header(HttpHeaders.MCP_SESSION_ID)
            assertTrue(session != null, "the opening exchange issued no session to continue on")

            val at = "${door.address}$TOOLS_PATH/${project.slug}"
            listOf("GET", "DELETE").forEach { method ->
                assertEquals(
                    UNAUTHORIZED,
                    sentTo(at, method, presenting = null, session = session)?.status,
                    "a $method on an open connection was served without a token",
                )
            }
        }
    }
}
