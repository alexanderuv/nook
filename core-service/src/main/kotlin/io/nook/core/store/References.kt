package io.nook.core.store

import io.nook.core.db.ProjectItemTable
import io.nook.core.db.ProjectTable
import io.nook.core.db.ReleaseTable
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll

// Reference resolution, shared by both paths so that a reference means the same
// thing whichever side reads it. A reference in UUID form resolves as an id,
// anything else as a slug. Item and release lookups are scoped to their project
// even for ids — an entity that exists only in another project is "not found",
// never reachable.
//
// Only live rows resolve. A deleted row keeps its id and its former handle in
// the store, and neither reaches it: to a caller, deletion is indistinguishable
// from having never existed. This is the single place that rule is written for
// a fetch, so no operation can forget it.
//
// These functions query the database, so they must run inside an open
// transaction.

// A canonical UUID rendering: five hyphen-separated hex groups of 8-4-4-4-12.
// Used both to decide that a reference is an id rather than a slug, and to
// reject explicit slugs that could never be referenced (id resolution would
// always win).
private val uuidShape =
    Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)

/** True when [ref] is written in UUID form and therefore resolves as an id, never a slug. */
internal fun isUuidShaped(ref: String): Boolean = uuidShape.matches(ref)

internal fun parseUuidOrNull(ref: String): Uuid? =
    if (isUuidShaped(ref)) Uuid.parse(ref) else null

/** Projects resolve instance-wide: by id, or by slug (unique among live projects). */
internal fun resolveProject(ref: String): ResultRow {
    val id = parseUuidOrNull(ref)
    val identity = if (id != null) ProjectTable.id eq id else ProjectTable.slug eq ref
    return ProjectTable.selectAll()
        .where { identity and ProjectTable.deletedAt.isNull() }
        .firstOrNull()
        ?: notFound("no project matches reference \"$ref\"")
}

internal fun resolveItem(projectId: Uuid, ref: String): ResultRow {
    val id = parseUuidOrNull(ref)
    val identity = if (id != null) ProjectItemTable.id eq id else ProjectItemTable.slug eq ref
    return ProjectItemTable.selectAll()
        .where {
            (ProjectItemTable.projectId eq projectId) and identity and ProjectItemTable.deletedAt.isNull()
        }
        .firstOrNull()
        ?: notFound("no item in the project matches reference \"$ref\"")
}

internal fun resolveRelease(projectId: Uuid, ref: String): ResultRow {
    val id = parseUuidOrNull(ref)
    val identity = if (id != null) ReleaseTable.id eq id else ReleaseTable.slug eq ref
    return ReleaseTable.selectAll()
        .where {
            (ReleaseTable.projectId eq projectId) and identity and ReleaseTable.deletedAt.isNull()
        }
        .firstOrNull()
        ?: notFound("no release in the project matches reference \"$ref\"")
}
