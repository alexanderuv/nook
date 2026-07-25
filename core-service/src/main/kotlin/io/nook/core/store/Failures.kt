package io.nook.core.store

import io.nook.contract.ErrorCode
import io.nook.contract.StructuredError
import io.nook.contract.StructuredErrorException

// The two failures both paths raise. Every caller-facing failure is decided in
// application code, before the store is asked to do anything; a rejection
// coming back from the store means validation missed a case, which is a fault
// in this service and travels as one.
//
// The remaining two codes — conflict and cycle — are the write path's alone and
// live there. A read can only fail because the caller asked for something
// impossible or for something that is not there.

internal fun validationFailed(message: String): Nothing =
    throw StructuredErrorException(StructuredError(ErrorCode.VALIDATION_FAILED, message))

internal fun notFound(message: String): Nothing =
    throw StructuredErrorException(StructuredError(ErrorCode.NOT_FOUND, message))
