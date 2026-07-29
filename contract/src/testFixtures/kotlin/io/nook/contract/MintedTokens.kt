package io.nook.contract

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

// The tokens the checks present, minted here rather than issued by anything:
// Nook runs no server that hands tokens out, and the milestone's own token is
// minted once by hand and written into a caller's configuration.
//
// They ship beside the stand-in core because both adapters' checks present the
// same ones, and two sets free to differ would leave "the identity is the same
// whichever adapter a call arrives at" untested.

/**
 * The secret the checks sign with. Exactly 256 bits, which is the least the
 * signing accepts — so a secret one character shorter is the unusable one, and
 * neither value has to be maintained against the other.
 */
public const val THE_SECRET: String = "a-secret-long-enough-for-signing"

/** A second secret, for the tokens an adapter was never configured to accept. */
public const val ANOTHER_SECRET: String = "a-quite-different-signing-secret"

/** The person most of these checks are about. */
public const val ALEX: String = "alex"

/** A second person, for the checks about two identities not taking each other's. */
public const val JORDAN: String = "jordan"

/**
 * A person's name in emoji and non-Latin script, which must come back exactly
 * as the token held it — an adapter that normalised anything would credit a row to
 * somebody the token never named.
 */
public const val WRITTEN_IN_ANOTHER_SCRIPT: String = "søk-🔍-用户"

/** How long a minted token holds up, unless a check asks for one that no longer does. */
private val AN_HOUR: Instant get() = Instant.now().plus(1, ChronoUnit.HOURS)

/**
 * A token naming [subject], signed with [secret], issued for [recipient], and
 * holding up until [until].
 *
 * [subject] being nothing mints a token carrying no `sub` claim at all, which is
 * a different token from one whose `sub` is empty — both are refused, and for
 * different reasons.
 */
public fun tokenFor(
    subject: String?,
    secret: String = THE_SECRET,
    recipient: String = NOOK,
    until: Instant = AN_HOUR,
): String {
    val claims = JWTClaimsSet.Builder()
        .audience(recipient)
        .expirationTime(Date.from(until))
    subject?.let { claims.subject(it) }
    return SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims.build())
        .also { it.sign(MACSigner(secret.toByteArray(Charsets.UTF_8))) }
        .serialize()
}

/**
 * A token carrying claims and no signature at all — well formed, and vouched
 * for by nobody.
 */
public fun tokenSignedWithNothing(subject: String = ALEX): String =
    PlainJWT(
        JWTClaimsSet.Builder()
            .subject(subject)
            .audience(NOOK)
            .expirationTime(Date.from(AN_HOUR))
            .build(),
    ).serialize()

/** Text presented where a token was expected, which is not one. */
public const val NOT_A_TOKEN: String = "this is not a token"

/** [token], as the `Authorization` header a caller presenting it would carry. */
public fun presenting(token: String): String = "Bearer $token"
