package io.nook.core.write

import io.nook.contract.ErrorCode
import io.nook.contract.StructuredError
import io.nook.contract.StructuredErrorException

// The two failures only a write can raise. The other two — validation_failed
// and not_found, which both paths raise — live beside reference resolution in
// io.nook.core.store.
//
// Every caller-facing failure is decided here, in application code, before the
// store is asked to do anything. The schema's constraints stay on underneath as
// a structural backstop, but they are never the arbiter of what a caller is
// told: each rule they enforce is checked first, inside the row lock that makes
// such a check authoritative. So a rejection coming back from the store
// means validation missed a case — a fault in this service, not a caller
// mistake, and it travels as one.

internal fun conflict(message: String): Nothing =
    throw StructuredErrorException(StructuredError(ErrorCode.CONFLICT, message))

internal fun cycleRejected(message: String): Nothing =
    throw StructuredErrorException(StructuredError(ErrorCode.CYCLE, message))
