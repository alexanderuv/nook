package io.nook.core.write

import io.nook.core.store.isUuidShaped

// Slug rules as pure functions: no database, no clock, no state. The write
// service applies them under its per-project lock; the schema's unique indexes
// remain the backstop if the application-level check is ever bypassed.

private val explicitSlugShape = Regex("[a-z0-9-]+")

/**
 * Room kept free at the end of a derived slug for a disambiguating suffix:
 * `-999999` and everything shorter.
 *
 * Reserved when the base is derived, not when a suffix is appended, and that
 * ordering is the whole point. Every candidate for one name has to keep the base
 * as its prefix, because the set of taken slugs is found by scanning for that
 * prefix. Cutting the base to make room for a suffix would produce a slug the
 * scan can no longer see — so the next caller with the same name would be
 * handed a handle already in use, and find out from the store.
 */
private const val SUFFIX_ROOM = 7

/** The longest base a derivation may produce, leaving [SUFFIX_ROOM] to grow into. */
private const val MAX_DERIVED_BASE_LENGTH = MAX_SLUG_LENGTH - SUFFIX_ROOM

/** The largest suffix that fits [SUFFIX_ROOM]. */
private const val MAX_SUFFIX = 999_999

/**
 * Derives a slug from a display name: lowercased, every run of characters
 * outside a–z and 0–9 collapsed to a single hyphen, hyphens trimmed from both
 * ends, and cut to leave room for a suffix. May be empty (e.g. for a name of
 * only punctuation) — the caller decides whether that is an error.
 *
 * A name may be more than twice as wide as a slug, so the cut is what keeps a
 * legal name from deriving a slug the store cannot hold. Trimming runs again
 * after it, because the cut can land on a hyphen.
 */
internal fun deriveSlug(name: String): String =
    name.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(MAX_DERIVED_BASE_LENGTH)
        .trimEnd('-')

/**
 * Validates a caller-supplied slug, returning a plain-words problem
 * description, or null when the slug is acceptable. Never rewrites the value:
 * an unacceptable explicit slug is the caller's to fix.
 *
 * An explicit slug may fill the whole column, unlike a derived one: it is taken
 * exactly as given or refused, and never suffixed, so it needs no room to grow.
 *
 * The shape rule admits no NUL, so the storable-text rule that guards every
 * other caller string has nothing left to say about a slug.
 */
internal fun explicitSlugProblem(slug: String): String? = when {
    slug.isEmpty() -> "an explicit slug must not be empty"
    slug.length > MAX_SLUG_LENGTH ->
        "an explicit slug must be at most $MAX_SLUG_LENGTH characters; this one is ${slug.length}"
    !slug.matches(explicitSlugShape) ->
        "an explicit slug may contain only lowercase letters a-z, digits 0-9, and hyphens"
    isUuidShaped(slug) ->
        "an explicit slug must not be in UUID form: references in UUID form always resolve as ids, so this slug could never be used"
    else -> null
}

/**
 * Picks the first free slug for a derived base: the base itself when untaken,
 * else the base with the first free numeric suffix (-2, -3, ...). Null when
 * every suffix that fits is taken, which the caller reports as a name it cannot
 * derive a handle from.
 */
internal fun firstFreeSlug(base: String, taken: Set<String>): String? {
    if (base !in taken) return base
    return (2..MAX_SUFFIX).firstNotNullOfOrNull { suffix ->
        "$base-$suffix".takeIf { it !in taken }
    }
}
