package io.nook.core.write

// Slug rules as pure functions: no database, no clock, no state. The write
// service applies them under its per-project lock; the schema's unique
// constraints remain the backstop if the application-level check is ever
// bypassed.

// A canonical UUID rendering: five hyphen-separated hex groups of 8-4-4-4-12.
// Used both to decide that a reference is an id rather than a slug, and to
// reject explicit slugs that could never be referenced (id resolution would
// always win).
private val uuidShape =
    Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)

private val explicitSlugShape = Regex("[a-z0-9-]+")

/** True when [ref] is written in UUID form and therefore resolves as an id, never a slug. */
internal fun isUuidShaped(ref: String): Boolean = uuidShape.matches(ref)

/**
 * Derives a slug from a display name: lowercased, every run of characters
 * outside a–z and 0–9 collapsed to a single hyphen, hyphens trimmed from both
 * ends. May be empty (e.g. for a name of only punctuation) — the caller
 * decides whether that is an error.
 */
internal fun deriveSlug(name: String): String =
    name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

/**
 * Validates a caller-supplied slug, returning a plain-words problem
 * description, or null when the slug is acceptable. Never rewrites the value:
 * an unacceptable explicit slug is the caller's to fix.
 */
internal fun explicitSlugProblem(slug: String): String? = when {
    slug.isEmpty() -> "an explicit slug must not be empty"
    !slug.matches(explicitSlugShape) ->
        "an explicit slug may contain only lowercase letters a-z, digits 0-9, and hyphens"
    isUuidShaped(slug) ->
        "an explicit slug must not be in UUID form: references in UUID form always resolve as ids, so this slug could never be used"
    else -> null
}

/**
 * Picks the first free slug for a derived base: the base itself when
 * untaken, else the base with the first free numeric suffix (-2, -3, ...).
 */
internal fun firstFreeSlug(base: String, taken: Set<String>): String {
    if (base !in taken) return base
    var suffix = 2
    while ("$base-$suffix" in taken) suffix++
    return "$base-$suffix"
}
