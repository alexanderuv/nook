package io.nook.core.write

import io.nook.core.store.validationFailed

// The bounds on caller-supplied text. Both rules exist because the store's own
// refusal arrives in a form no caller can act on: a value wider than its column
// is rejected by the driver before a statement is even sent, and a NUL is
// rejected by PostgreSQL in the middle of one. Neither arrives as an
// ExposedSQLException the write path could translate, so both would surface as
// an internal fault for what is an ordinary caller mistake. Deciding them here
// is what makes the answer validation_failed.
//
// The lengths mirror the column widths in the changelog; a test pins them to the
// migrated schema, so the two cannot drift apart unnoticed.

/** The width of every `name` column. */
internal const val MAX_NAME_LENGTH = 500

/** The width of every `slug` column. */
internal const val MAX_SLUG_LENGTH = 200

/**
 * Refuses text the store could not hold, naming the [field] so the caller knows
 * which value to fix. [maxLength] is left at its default for the columns that
 * have no width of their own — a description is `text` — where the NUL rule is
 * the only one that applies.
 *
 * The NUL test is written on the code point rather than a character literal: the
 * character itself is invisible in source, and an escape for it is one stray
 * edit away from being read as four ordinary ones.
 */
internal fun requireStorableText(value: String, field: String, maxLength: Int = Int.MAX_VALUE) {
    if (value.any { it.code == 0 }) {
        validationFailed("a $field must not contain a NUL character")
    }
    if (value.length > maxLength) {
        validationFailed("a $field must be at most $maxLength characters; this one is ${value.length}")
    }
}
