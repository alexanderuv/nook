package io.nook.core.write

import io.nook.contract.ErrorCode
import io.nook.contract.StructuredError
import io.nook.contract.StructuredErrorException
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.postgresql.util.PSQLException

// The write path's failure surface. Application-level validation throws
// through the helpers below; database constraint violations that slip past
// validation are translated by constraint name — the name arrives reliably on
// the driver's exception, so no message parsing is needed.

internal fun validationFailed(message: String): Nothing =
    throw StructuredErrorException(StructuredError(ErrorCode.VALIDATION_FAILED, message))

internal fun notFound(message: String): Nothing =
    throw StructuredErrorException(StructuredError(ErrorCode.NOT_FOUND, message))

internal fun conflict(message: String): Nothing =
    throw StructuredErrorException(StructuredError(ErrorCode.CONFLICT, message))

internal fun cycleRejected(message: String): Nothing =
    throw StructuredErrorException(StructuredError(ErrorCode.CYCLE, message))

/**
 * The error code behind each named constraint of the structure schema.
 * Uniqueness rules (unique indexes and primary keys) translate to [ErrorCode.CONFLICT];
 * reference rules (foreign keys) and CHECK rules to [ErrorCode.VALIDATION_FAILED].
 * [ErrorCode.CYCLE] never appears here: only the application's cycle walk produces it.
 */
internal val constraintErrorCodes: Map<String, ErrorCode> = mapOf(
    "uq_project_slug" to ErrorCode.CONFLICT,
    "uq_item_project_slug" to ErrorCode.CONFLICT,
    "uq_release_project_slug" to ErrorCode.CONFLICT,
    "uq_item_project_id" to ErrorCode.CONFLICT,
    "uq_release_project_id" to ErrorCode.CONFLICT,
    "uq_document_path" to ErrorCode.CONFLICT,
    "pk_project" to ErrorCode.CONFLICT,
    "pk_release" to ErrorCode.CONFLICT,
    "pk_project_item" to ErrorCode.CONFLICT,
    "pk_item_dependency" to ErrorCode.CONFLICT,
    "pk_document" to ErrorCode.CONFLICT,
    "pk_document_sequence" to ErrorCode.CONFLICT,
    "fk_release_project" to ErrorCode.VALIDATION_FAILED,
    "fk_item_project" to ErrorCode.VALIDATION_FAILED,
    "fk_item_parent_same_project" to ErrorCode.VALIDATION_FAILED,
    "fk_item_release_same_project" to ErrorCode.VALIDATION_FAILED,
    "fk_dep_item" to ErrorCode.VALIDATION_FAILED,
    "fk_dep_blocker" to ErrorCode.VALIDATION_FAILED,
    "fk_doc_project" to ErrorCode.VALIDATION_FAILED,
    "fk_doc_item_same_project" to ErrorCode.VALIDATION_FAILED,
    "fk_docseq_project" to ErrorCode.VALIDATION_FAILED,
    "ck_dep_no_self_block" to ErrorCode.VALIDATION_FAILED,
)

/**
 * Translates a database rejection into the structured error for the violated
 * constraint, or returns null when the failure names no known constraint —
 * an unknown failure must propagate as what it is, not be dressed up.
 */
internal fun translateConstraintViolation(failure: ExposedSQLException): StructuredErrorException? {
    val constraintName = (failure.cause as? PSQLException)?.serverErrorMessage?.constraint ?: return null
    val code = constraintErrorCodes[constraintName] ?: return null
    return StructuredErrorException(
        StructuredError(code, "the database rejected the write: rule $constraintName was violated"),
    )
}
