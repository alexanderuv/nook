package io.nook.contract

import kotlinx.serialization.Serializable

/**
 * Every way an operation can refuse a caller. A failure carrying one of these
 * codes says the request was wrong and describes how; the caller can act on it.
 *
 * This is not every way an operation can fail. A fault inside the service — a
 * store that refused a write validation should have refused first, a schema
 * missing a row the write path requires — carries no code, because there is
 * nothing for a caller to do differently. Those travel as ordinary exceptions,
 * and the difference between the two is the point: a code means "fix the
 * request", an exception means "this service is broken".
 */
@Serializable(with = ErrorCodeSerializer::class)
public enum class ErrorCode(public val label: String) {
    VALIDATION_FAILED("validation_failed"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    CYCLE("cycle"),
}

/** The structured payload every refused operation returns: code, message, details. */
@Serializable
public data class StructuredError(
    public val code: ErrorCode,
    public val message: String,
    public val details: Map<String, String>? = null,
)

/** Carries a [StructuredError] out of a refused operation. */
public class StructuredErrorException(public val error: StructuredError) : RuntimeException(error.message)

/** The detail a `not_found` carries to say which of the things a call named was the one that was not there. */
private const val MISSING = "missing"

/**
 * Which of the things a call named could not be found.
 *
 * A call names several: the project it acts inside, and often an item or a
 * release within it. A refusal saying "not found" says one of them matched
 * nothing but not which, and the difference decides what the caller does — a
 * missing item is a reference to correct, while a missing project means the
 * caller is working somewhere that no longer exists and everything it does next
 * will fail the same way.
 *
 * It travels in the details a refusal already carries rather than in its
 * message, so reading it needs no guessing at wording.
 */
public enum class Missing(public val label: String) {
    PROJECT("project"),
    ITEM("item"),
    RELEASE("release"),
    ;

    /** This, as the details of the refusal reporting it. */
    public fun asDetails(): Map<String, String> = mapOf(MISSING to label)

    public companion object {

        /** What [error] says was missing, or nothing where it does not say. */
        public fun of(error: StructuredError): Missing? =
            error.details?.get(MISSING)?.let { said -> entries.firstOrNull { it.label == said } }
    }
}

/**
 * Where a call that ended without a verdict came apart. A breakdown is not a
 * refusal: there is nothing for a caller to fix, which is exactly why it must
 * never read as though there were.
 */
public enum class BreakdownOrigin(public val label: String) {
    /** The core answered, and what it answered is that something inside it broke. */
    CORE("core"),

    /** No answer arrived at all: nothing listening, the link dropped, or the wait ran out. */
    CONNECTION("connection"),
}

/**
 * A call across the connection that produced no verdict.
 *
 * The two origins are kept apart because a caller acts on them differently: a
 * core that broke has a defect to report, while a core that could not be
 * reached may simply not be up yet — and the same caller succeeds later without
 * being rebuilt.
 */
public class BreakdownException internal constructor(
    public val origin: BreakdownOrigin,
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause)
