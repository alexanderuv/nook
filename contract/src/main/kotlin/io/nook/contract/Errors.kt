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
public enum class ErrorCode(override val label: String) : Labelled {
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
public enum class Missing(override val label: String) : Labelled {
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

        /**
         * The same, read off the error object a failure travels in — so a
         * surface that carries the standard's shape outward reads the fact from
         * the shape it carries rather than from one it used to.
         */
        public fun of(error: RpcError): Missing? = error.asStructuredError()?.let(::of)
    }
}

/**
 * Where a call that ended without a verdict came apart. A breakdown is not a
 * refusal: there is nothing for a caller to fix, which is exactly why it must
 * never read as though there were.
 */
public enum class BreakdownOrigin(override val label: String) : Labelled {
    /**
     * The core answered, and its answer settled nothing: it says something
     * inside it broke, or it is something this build cannot read at all.
     */
    CORE("core"),

    /** No answer arrived at all: nothing listening, the link dropped, or the wait ran out. */
    CONNECTION("connection"),
}

/**
 * A call across the connection that produced no verdict.
 *
 * The two origins are kept apart because *this library* acts on them
 * differently, and what separates them is whether anything came back. An answer
 * that arrived and settled nothing will settle nothing on a second attempt
 * either, so there is a defect to report — the core's own, or a version of it
 * this build was never written against. A core that could not be reached may
 * simply not be up yet, and the same caller succeeds later without being
 * rebuilt.
 *
 * That distinction stops here. What this says out loud is [NO_VERDICT] and
 * nothing more, whichever origin it carries, because a caller can do nothing
 * differently for knowing and a reply naming a part behind the surface makes
 * that part a promise. What was actually observed rides in the cause, where a
 * stack trace carries it and a reply does not.
 */
public class BreakdownException internal constructor(
    public val origin: BreakdownOrigin,
    cause: Throwable?,
) : RuntimeException(NO_VERDICT, cause)
