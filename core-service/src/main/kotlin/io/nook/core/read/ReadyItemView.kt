package io.nook.core.read

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * The `ready_item` view, declared so it can be queried like a table.
 *
 * The view holds the readiness rule itself — a leaf that is `todo` with every
 * blocker done or cancelled — and it is deliberately the only place that rule is
 * written. Recomputing it here would put the same rule in two places, and the
 * two would drift.
 *
 * It mirrors `project_item` column for column, because that is what the view
 * selects. Only [id] and [projectId] are read: readiness decides *which* items
 * come back, and `project_item` supplies *what* each one is, so the row-to-entity
 * mapping has a single home. The rest are declared because they are there —
 * a declaration that quietly omits half a relation is a trap for the next
 * reader.
 *
 * Two things this declaration does NOT do, both settled by measurement rather
 * than assumption. It does not belong in the set the drift guard compares:
 * pointed at a view, the guard does not recognise one and proposes creating a
 * table. And it does not confine itself to reading — a view this simple is
 * writable, and PostgreSQL will pass an insert straight through into
 * `project_item`. What stops that is the read-only transaction, not this file.
 */
internal object ReadyItemView : Table("ready_item") {
    val id = uuid("id")
    val projectId = uuid("project_id")
    val parentId = uuid("parent_id").nullable()
    val releaseId = uuid("release_id").nullable()
    val type = short("type")
    val slug = varchar("slug", 200)
    val name = varchar("name", 500)
    val description = text("description").nullable()
    val status = short("status")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val createdBy = varchar("created_by", 200)
    val updatedBy = varchar("updated_by", 200)
}
