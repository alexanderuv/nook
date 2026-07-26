package io.nook.contract

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
public enum class ErrorCode(public val label: String) {
    VALIDATION_FAILED("validation_failed"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    CYCLE("cycle"),
}

/** The structured payload every refused operation returns: code, message, details. */
public data class StructuredError(
    public val code: ErrorCode,
    public val message: String,
    public val details: Map<String, String>? = null,
)

/** Carries a [StructuredError] out of a refused operation. */
public class StructuredErrorException(public val error: StructuredError) : RuntimeException(error.message)
