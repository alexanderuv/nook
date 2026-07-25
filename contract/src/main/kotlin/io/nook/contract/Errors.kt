package io.nook.contract

/** The whole error surface: every failed operation carries one of these codes. */
enum class ErrorCode(val label: String) {
    VALIDATION_FAILED("validation_failed"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    CYCLE("cycle"),
}

/** The structured payload every failed operation returns: code, message, details. */
data class StructuredError(
    val code: ErrorCode,
    val message: String,
    val details: Map<String, String>? = null,
)

/** Carries a [StructuredError] out of a failed operation. */
class StructuredErrorException(val error: StructuredError) : RuntimeException(error.message)
