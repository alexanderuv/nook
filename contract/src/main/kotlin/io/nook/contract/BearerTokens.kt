package io.nook.contract

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import java.text.ParseException

/**
 * The recipient a token must have been issued for, and the realm a refusal
 * names. One value at both doors rather than one each: the same token is
 * presented at either, and a setting for it would be a second copy of something
 * that must not differ.
 */
public const val NOOK: String = "nook"

/**
 * The most either identity may be. It is the width of the store's audit
 * columns, so a longer name is one no row could hold — which is why it is
 * checked where a call arrives rather than where a row is written.
 */
public const val MAX_ACTOR_LENGTH: Int = 200

/** The claim naming who a token is about, as the standard names it. */
private const val SUBJECT_CLAIM = "sub"

/** How the milestone's token is signed, and the only signing either door accepts. */
private val SIGNED_WITH: JWSAlgorithm = JWSAlgorithm.HS256

/** The one scheme a caller presents an already-issued identity under. */
private const val BEARER_PREFIX = "Bearer "

/** What a caller is told when its token is missing, malformed, or does not hold up. */
public const val NO_VALID_TOKEN: String = "this call presents no valid bearer token"

/** The header a refusal carries, so a caller learns how it is meant to present a token. */
public const val WWW_AUTHENTICATE: String = "WWW-Authenticate"

/**
 * The challenge that header carries, in the fuller form
 * [RFC 6750](https://www.rfc-editor.org/rfc/rfc6750) §3 defines: the realm, that
 * the token was what was wrong, and what was wrong with it. Two Ktor plugins
 * would produce the word `Bearer` and a realm and stop there, which tells a
 * caller nothing about which of the two kinds of refusal this is.
 */
public const val BEARER_CHALLENGE: String =
    """Bearer realm="$NOOK", error="invalid_token", error_description="$NO_VALID_TOKEN""""

/**
 * What a door is stopped with when the secret it was given could check no
 * token: absent, or too short for the signing it would have to verify.
 *
 * It carries none of the library's own words about key length, and the door
 * that catches it says which setting is at fault instead — an operator can act
 * on a setting's name and can do nothing with a number of bits.
 */
public class UnusableSecretException(said: String, cause: Throwable) : IllegalStateException(said, cause)

/**
 * The reading of a bearer token: a secret goes in, and the person a presented
 * token names comes out, or nobody.
 *
 * Both front doors mount this one and neither adds to it, which is what makes
 * "the identity is the same whichever door a call arrives at" a fact rather
 * than a promise two programs keep. It lives beside the shared answering
 * function for the same reason that one does.
 *
 * Nimbus is used in its assembled one-call form rather than through its
 * parse-then-verify calls. Those report a bad signature by *returning* a value:
 * one missing `if` accepts every token whoever signed it, and nothing about the
 * code looks wrong. This form has no such answer to forget.
 *
 * What no token library checks is what is *in* the claim — every one of them
 * hands back an empty name, a name of spaces, a name of any length, or a name
 * holding a NUL character without complaint. Those are Nook's own four checks,
 * and they belong here rather than inside an operation: a call that cannot be
 * attributed must not reach the core at all.
 *
 * A [secret] too short for the signing it is asked to check stops this at the
 * moment it is built, which is what turns "started with nothing to check tokens
 * against" into a program that stops rather than one that serves.
 */
public class BearerTokens(secret: String) {

    private val secretBytes = secret.toByteArray(Charsets.UTF_8)

    init {
        // Building a verifier is the whole of the check, so what it produces is
        // not kept: it is the library's own rule about key length being
        // consulted at startup, rather than a byte count copied here to drift
        // from it. Left to the processor below, the same rule would first be
        // applied to the first token to arrive — by which time the door is
        // already serving.
        try {
            MACVerifier(secretBytes)
        } catch (couldCheckNothing: JOSEException) {
            throw UnusableSecretException("this secret could check no token: $couldCheckNothing", couldCheckNothing)
        }
    }

    private val tokens = DefaultJWTProcessor<SecurityContext>().apply {
        jwsKeySelector = JWSVerificationKeySelector(SIGNED_WITH, ImmutableSecret(secretBytes))
        // Expiry is the library's own business and needs no saying. The
        // recipient and the presence of a subject are named because a token
        // issued for something else, or naming nobody, is one this cannot act
        // on however well it verifies.
        jwtClaimsSetVerifier = DefaultJWTClaimsVerifier(NOOK, null, setOf(SUBJECT_CLAIM))
    }

    /**
     * The person an `Authorization` header presents, or nobody — which covers a
     * header that is absent, one carrying some other scheme, and one carrying a
     * token that does not hold up.
     */
    public fun subjectPresenting(authorization: String?): String? {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
        return subjectOf(authorization.substring(BEARER_PREFIX.length).trim())
    }

    /**
     * The person [token] names, or nobody.
     *
     * What comes back comes back exactly as the token held it: emoji,
     * non-Latin script and spaces inside a name are recorded verbatim, and
     * trimming or lowercasing here would record somebody the token never named.
     */
    public fun subjectOf(token: String): String? {
        val subject = try {
            tokens.process(token, null).subject
        } catch (unreadable: ParseException) {
            return null
        } catch (doesNotHoldUp: BadJOSEException) {
            return null
        } catch (unverifiable: JOSEException) {
            return null
        }
        return subject?.takeIf { it.isUsableActorName() }
    }
}

/**
 * Whether a name is one the store can attribute a row to: something other than
 * blank, short enough for the column, and free of the one character a text
 * column cannot hold.
 *
 * Spaces *inside* a name are none of this rule's business — a name is recorded
 * as the token held it, and only the four shapes here are refused.
 *
 * The NUL test is written on the code point rather than a character literal:
 * the character itself is invisible in source, and an escape for it is one
 * stray edit away from being read as four ordinary characters.
 */
internal fun String.isUsableActorName(): Boolean =
    isNotBlank() && length <= MAX_ACTOR_LENGTH && none { it.code == 0 }
