package io.nook.core.read

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * The `ready_item` view, declared so it can be queried like a table.
 *
 * It lives among the tests because no operation reads it. The schema still
 * builds it, and what it says is still true — a leaf that is `todo` with every
 * blocker done or cancelled — but the question it answers is now asked by
 * combining ordinary filter parts, so the view backs nothing. Whether it should
 * be dropped from the schema is a separate decision; until it is taken, the
 * declaration belongs where its one remaining reader is.
 *
 * That reader is [ReadTransactionTest], and what it needs the view for is
 * precisely what makes a view different from a table: a view this simple is
 * writable, and PostgreSQL will pass an insert straight through into
 * `project_item`. What stops that is the read-only transaction, which is the
 * claim the test makes and the reason a plain table would not prove it.
 *
 * It mirrors `project_item` column for column, because that is what the view
 * selects — a declaration that quietly omits half a relation is a trap for the
 * next reader. It is deliberately absent from the set the drift guard compares:
 * pointed at a view, the guard does not recognise one and proposes creating a
 * table.
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
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val createdBy = varchar("created_by", 200)
    val updatedBy = varchar("updated_by", 200)
}
