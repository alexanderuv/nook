package io.nook.contract

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The reading of a bearer token, against every shape of token a door will be
 * presented with.
 *
 * The table is the point: six of its rows are the standard's own business and
 * every token library refuses them, and five are Nook's, reaching a door as
 * ordinary tokens that verify — a door that skipped them would record an empty
 * person, a person made of spaces, or a name wider than any column that holds
 * one.
 */
class BearerTokenTest {

    private val reading = BearerTokens(THE_SECRET)

    private val aName200Long = "n".repeat(MAX_ACTOR_LENGTH)

    private val aName201Long = "n".repeat(MAX_ACTOR_LENGTH + 1)

    // The one character a text column cannot hold, built from its code point:
    // written as an escape it is one stray edit away from being read as four
    // ordinary characters, and written as itself it is invisible.
    private val aNameHoldingNul = "al${Char(0)}ex"

    @Test
    fun `a valid token names its person`() {
        assertEquals(ALEX, reading.subjectOf(tokenFor(ALEX)))
    }

    @Test
    fun `a person written in emoji and another script comes back exactly as the token held it`() {
        val read = reading.subjectOf(tokenFor(WRITTEN_IN_ANOTHER_SCRIPT))
        assertEquals(WRITTEN_IN_ANOTHER_SCRIPT, read)
        assertEquals(
            WRITTEN_IN_ANOTHER_SCRIPT.toByteArray(Charsets.UTF_8).toList(),
            read?.toByteArray(Charsets.UTF_8)?.toList(),
            "the person's name did not survive byte for byte",
        )
    }

    @Test
    fun `a name of exactly the width a column holds is a person, and one character more is not`() {
        assertEquals(aName200Long, reading.subjectOf(tokenFor(aName200Long)))
        assertNull(reading.subjectOf(tokenFor(aName201Long)))
    }

    @Test
    fun `the ten tokens a door must refuse name nobody`() {
        val refused = mapOf(
            "signed with something else" to tokenFor(ALEX, secret = ANOTHER_SECRET),
            "expired an hour ago" to tokenFor(ALEX, until = Instant.now().minus(1, ChronoUnit.HOURS)),
            "issued for another recipient" to tokenFor(ALEX, recipient = "somewhere-else"),
            "signed with nothing at all" to tokenSignedWithNothing(),
            "not a token at all" to NOT_A_TOKEN,
            "carrying no subject" to tokenFor(null),
            "an empty subject" to tokenFor(""),
            "a subject of only spaces" to tokenFor("   "),
            "a subject of 201 characters" to tokenFor(aName201Long),
            "a subject holding a NUL character" to tokenFor(aNameHoldingNul),
        )
        refused.forEach { (what, token) -> assertNull(reading.subjectOf(token), what) }
    }

    @Test
    fun `a header presenting a valid token names its person, and every other header names nobody`() {
        assertEquals(ALEX, reading.subjectPresenting(presenting(tokenFor(ALEX))))
        assertNull(reading.subjectPresenting(null), "no Authorization header at all")
        assertNull(reading.subjectPresenting(""), "an empty Authorization header")
        assertNull(reading.subjectPresenting("Basic ${tokenFor(ALEX)}"), "some other scheme")
        assertNull(reading.subjectPresenting(tokenFor(ALEX)), "a token presented under no scheme")
        assertNull(reading.subjectPresenting(presenting(NOT_A_TOKEN)), "a bearer scheme carrying no token")
    }

    @Test
    fun `a door with nothing usable to check tokens against stops when its reading is built`() {
        assertFailsWith<UnusableSecretException>("built with no secret at all") { BearerTokens("") }
        assertFailsWith<UnusableSecretException>("built with a secret under 256 bits") {
            BearerTokens(THE_SECRET.dropLast(1))
        }
        // And the least it accepts is accepted, so the two are pinned to each
        // other rather than to a number written here.
        BearerTokens(THE_SECRET)
    }
}
