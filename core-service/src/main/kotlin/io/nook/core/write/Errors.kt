package io.nook.core.write

import io.nook.contract.ErrorCode
import io.nook.contract.StructuredError
import io.nook.contract.StructuredErrorException

// The write path's failure surface: every caller-facing failure is decided here,
// in application code, before the store is asked to do anything. The schema's
// constraints stay on underneath as a structural backstop, but they are never
// the arbiter of what a caller is told — each rule they enforce is checked
// first, inside the advisory lock that makes such a check authoritative. So a
// rejection coming back from the store means validation missed a case: a fault
// in this service, not a caller mistake, and it travels as one.

internal fun validationFailed(message: String): Nothing =
    throw StructuredErrorException(StructuredError(ErrorCode.VALIDATION_FAILED, message))

internal fun notFound(message: String): Nothing =
    throw StructuredErrorException(StructuredError(ErrorCode.NOT_FOUND, message))

internal fun conflict(message: String): Nothing =
    throw StructuredErrorException(StructuredError(ErrorCode.CONFLICT, message))

internal fun cycleRejected(message: String): Nothing =
    throw StructuredErrorException(StructuredError(ErrorCode.CYCLE, message))
