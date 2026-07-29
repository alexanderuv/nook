package io.nook.web

import io.nook.contract.ALEX
import io.nook.contract.ANOTHER_SECRET
import io.nook.contract.Actor
import io.nook.contract.ErrorCode
import io.nook.contract.MAX_ACTOR_LENGTH
import io.nook.contract.NOT_A_TOKEN
import io.nook.contract.RpcReply
import io.nook.contract.WWW_AUTHENTICATE
import io.nook.contract.asStructuredError
import io.nook.contract.presenting
import io.nook.contract.tokenFor
import io.nook.contract.tokenSignedWithNothing
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a call presenting no valid bearer token comes back as. */
private const val UNAUTHORIZED = 401

/** What every call this door does answer comes back as, whatever the answer says. */
private const val ANSWERED = 200

/**
 * The gate on the web API: what it turns away, what it lets through, and that a
 * refusal of the token reads nothing like a refusal of the request.
 *
 * The second of those is the one worth stating. A caller told "your token did
 * not hold up" fixes its configuration; a caller told "there is no such field"
 * fixes its request. Answering both under one number, or letting the first
 * carry one of the four domain reasons, would leave a caller with no way to
 * tell which it is looking at.
 */
class GatedApiTest {

    private val doors = BothDoors()

    private val core = doors.core

    @AfterTest
    fun letGo() {
        doors.close()
    }

    /** Every token this door must refuse, under the name of what is wrong with it. */
    private val badTokens: Map<String, String?> = mapOf(
        "no Authorization header at all" to null,
        "a header carrying something that is not a bearer token" to "Something-Else abcdef",
        "signed with something else" to presenting(tokenFor(ALEX, secret = ANOTHER_SECRET)),
        "expired an hour ago" to presenting(tokenFor(ALEX, until = Instant.now().minus(1, ChronoUnit.HOURS))),
        "issued for another recipient" to presenting(tokenFor(ALEX, recipient = "somewhere-else")),
        "signed with nothing at all" to presenting(tokenSignedWithNothing()),
        "not a token at all" to presenting(NOT_A_TOKEN),
        "carrying no subject" to presenting(tokenFor(null)),
        "an empty subject" to presenting(tokenFor("")),
        "a subject of only spaces" to presenting(tokenFor("   ")),
        "a subject of 201 characters" to presenting(tokenFor("n".repeat(MAX_ACTOR_LENGTH + 1))),
    )

    @Test
    fun `every call presenting no valid token is refused, and none of them reaches the core`() {
        badTokens.forEach { (what, header) ->
            val answered = sentTo(doors.apiAddress, rawCall("list_projects"), presenting = header)

            assertEquals(UNAUTHORIZED, answered.status, what)
            assertTrue(
                answered.said.contains("no valid bearer token"),
                "$what came back saying: ${answered.said}",
            )
        }
        assertEquals(emptyList(), core.invocations, "a call presenting no valid token reached the core")
    }

    @Test
    fun `a refusal names how a caller is meant to present a token`() {
        val answered = sentTo(doors.apiAddress, rawCall("list_projects"), presenting = null)
        val challenge = answered.header(WWW_AUTHENTICATE)

        assertTrue(challenge != null, "a refusal carried no challenge at all")
        assertTrue(challenge.startsWith("Bearer"), "the challenge did not name the scheme; it said: $challenge")
        assertTrue(
            challenge.contains("realm=") && challenge.contains("invalid_token"),
            "the challenge said neither which realm nor that the token was the problem; it said: $challenge",
        )
    }

    @Test
    fun `a refused call leaves the door serving, and the next valid one is served normally`() {
        assertEquals(UNAUTHORIZED, sentTo(doors.apiAddress, rawCall("list_projects"), presenting = null).status)

        assertIs<RpcReply.Answered>(doors.answer(rawCall("list_projects")), "the next call was not served normally")
        assertEquals(1, core.invocations.size, "the refused call reached the core after all")
    }

    @Test
    fun `a call refused for its token and one refused for its contents are told apart`() {
        val forItsToken = sentTo(doors.apiAddress, rawCall("list_projects"), presenting = null)
        val forItsContents = sentTo(doors.apiAddress, rawCall("get_item", """{"project":"p","ref":"$NOT_ALLOWED"}"""))

        assertEquals(UNAUTHORIZED, forItsToken.status)
        assertEquals(ANSWERED, forItsContents.status, "a refusal of the request did not arrive as an answer")

        assertTrue(
            ErrorCode.entries.none { forItsToken.said.contains(it.label) },
            "a refusal of the token carried a domain reason; it said: ${forItsToken.said}",
        )
        assertEquals(
            ErrorCode.VALIDATION_FAILED,
            assertIs<RpcReply.Failed>(
                doors.answer(rawCall("get_item", """{"project":"p","ref":"$NOT_ALLOWED"}""")),
            ).error.asStructuredError()?.code,
            "a refusal of the request carried no domain reason",
        )
    }

    @Test
    fun `a caller naming somebody else in headers of its own is recorded as the person its token named`() {
        // The two crossing headers are what a *door* tells the core. A caller
        // writing them itself reaches a door that never reads them, and whose
        // only source for who the call is for is the token it presented.
        sentTo(
            doors.apiAddress,
            rawCall("create_project", """{"name":"Whose Word"}"""),
            naming = Actor("somebody-else", "some-other-agent"),
        )

        assertEquals(Actor(ALEX, null), core.invocations.single().actor)
    }

    @Test
    fun `the five fields a caller might name are each refused as a field the operation does not define`() {
        listOf("ownerSubject", "createdBy", "updatedBy", "createdByAgent", "updatedByAgent").forEach { field ->
            val body = rawCall("create_project", """{"name":"Smuggled","$field":"somebody-else"}""")
            val refused = assertIs<RpcReply.Failed>(doors.answer(body), field)

            assertEquals(ErrorCode.VALIDATION_FAILED, refused.error.asStructuredError()?.code, field)
            assertTrue(
                refused.error.message.contains(field),
                "the refusal did not name $field; it said: ${refused.error.message}",
            )
        }
        assertEquals(emptyList(), core.invocations, "a call naming one of the five reached the core")
    }

    @Test
    fun `every call through this door names its person and no acting agent`() {
        doors.answer(rawCall("list_projects"))
        doors.answer(rawCall("create_project", """{"name":"Search revamp"}"""))

        assertEquals(listOf(Actor(ALEX, null), Actor(ALEX, null)), core.invocations.map { it.actor })
        assertNull(core.invocations.first().actor.agent, "a person working directly was credited to an agent")
    }
}
