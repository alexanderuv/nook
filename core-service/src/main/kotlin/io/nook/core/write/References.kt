package io.nook.core.write

import io.nook.core.db.ProjectItemTable
import io.nook.core.db.ProjectTable
import io.nook.core.db.ReleaseTable
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.selectAll

// Reference resolution: a reference in UUID form resolves as an id, anything
// else as a slug. Item and release lookups are scoped to their project even
// for ids — an entity that exists only in another project is "not found",
// never reachable. These functions query the database, so they must run
// inside an open transaction.

internal fun parseUuidOrNull(ref: String): Uuid? =
    if (isUuidShaped(ref)) Uuid.parse(ref) else null

/** Projects resolve instance-wide: by id, or by slug (unique across the instance). */
internal fun resolveProject(ref: String): ResultRow {
    val id = parseUuidOrNull(ref)
    val query = if (id != null) {
        ProjectTable.selectAll().where { ProjectTable.id eq id }
    } else {
        ProjectTable.selectAll().where { ProjectTable.slug eq ref }
    }
    return query.firstOrNull() ?: notFound("no project matches reference \"$ref\"")
}

internal fun resolveItem(projectId: Uuid, ref: String): ResultRow {
    val id = parseUuidOrNull(ref)
    val query = if (id != null) {
        ProjectItemTable.selectAll()
            .where { (ProjectItemTable.projectId eq projectId) and (ProjectItemTable.id eq id) }
    } else {
        ProjectItemTable.selectAll()
            .where { (ProjectItemTable.projectId eq projectId) and (ProjectItemTable.slug eq ref) }
    }
    return query.firstOrNull() ?: notFound("no item in the project matches reference \"$ref\"")
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
    return query.firstOrNull() ?: notFound("no release in the project matches reference \"$ref\"")
}
