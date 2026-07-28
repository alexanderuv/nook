package io.nook.core.store

import io.nook.contract.Missing
import io.nook.core.db.ProjectItemTable
import io.nook.core.db.ProjectTable
import io.nook.core.db.ReleaseTable
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

// Reference resolution, shared by both paths so that a reference means the same
// thing whichever side reads it. A reference in UUID form resolves as an id,
// anything else as a slug. Item and release lookups are scoped to their project
// even for ids — an entity that exists only in another project is "not found",
// never reachable.
//
// A deleted row is not a case these functions handle, because deleting removes
// the row: there is nothing left to match, and "not found" falls out of the
// query rather than being enforced on top of it.
//
// These functions query the database, so they must run inside an open
// transaction.

/**
 * The id [ref] names, or null when it names a slug instead.
 *
 * UUID form here means the canonical rendering and only that: five
 * hyphen-separated hex groups of 8-4-4-4-12, in either case. It has to be the
 * strict parser: the lenient ones left-pad short groups, so a legal slug like
 * `2026-07-25-0-1` parses as an id and the lookup goes after a row its caller
 * never named.
 */
internal fun parseUuidOrNull(ref: String): Uuid? = Uuid.parseHexDashOrNull(ref)

/**
 * True when [ref] is written in UUID form and therefore resolves as an id,
 * never a slug. Also what makes such a slug unacceptable to accept in the first
 * place: id resolution would always win, leaving it unreferenceable.
 */
internal fun isUuidShaped(ref: String): Boolean = parseUuidOrNull(ref) != null

/**
 * What it means for a project row to be the one [ref] names: its id when the
 * reference is in UUID form, its slug otherwise. Separate from [resolveProject]
 * because the write path locks the same row it resolves, in one statement, and
 * both sides must agree on which row that is.
 */
internal fun projectIdentity(ref: String): Op<Boolean> {
    val id = parseUuidOrNull(ref)
    return if (id != null) ProjectTable.id eq id else ProjectTable.slug eq ref
}

/** Projects resolve instance-wide: by id, or by slug (unique across the instance). */
internal fun resolveProject(ref: String): ResultRow =
    ProjectTable.selectAll()
        .where(projectIdentity(ref))
        .firstOrNull()
        ?: notFound(Missing.PROJECT, "no project matches reference \"$ref\"")

internal fun resolveItem(projectId: Uuid, ref: String): ResultRow {
    val id = parseUuidOrNull(ref)
    val query = if (id != null) {
        ProjectItemTable.selectAll()
            .where { (ProjectItemTable.projectId eq projectId) and (ProjectItemTable.id eq id) }
    } else {
        ProjectItemTable.selectAll()
            .where { (ProjectItemTable.projectId eq projectId) and (ProjectItemTable.slug eq ref) }
    }
    return query.firstOrNull() ?: notFound(Missing.ITEM, "no item in the project matches reference \"$ref\"")
}

internal fun resolveRelease(projectId: Uuid, ref: String): ResultRow {
    val id = parseUuidOrNull(ref)
    val query = if (id != null) {
        ReleaseTable.selectAll()
            .where { (ReleaseTable.projectId eq projectId) and (ReleaseTable.id eq id) }
    } else {
        ReleaseTable.selectAll()
            .where { (ReleaseTable.projectId eq projectId) and (ReleaseTable.slug eq ref) }
    }
    return query.firstOrNull() ?: notFound(Missing.RELEASE, "no release in the project matches reference \"$ref\"")
}
