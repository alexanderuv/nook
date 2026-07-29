package io.nook.system

import java.util.UUID
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The sequence Nook exists to serve: a release, an epic, two tasks under it, a
 * leaf on the project itself, the epic put in the release, one task made to
 * wait on the other, and then one listing call asking what there is to work on.
 *
 * The names are written once because both adapters carry the same loop out —
 * one over MCP and one over the web API — and two sets of names free to differ
 * would let the two runs drift into different shapes while both passing.
 */
internal object TheLoop {

    const val RELEASE: String = "the first release"

    const val EPIC: String = "the epic the work sits under"

    const val FIRST_TASK: String = "the task that goes first"

    const val SECOND_TASK: String = "the task that waits for the first"

    const val BUG: String = "a bug that sits on the project itself"

    /** The types that hold no other item, which is what "what should I do next" asks about. */
    val LEAF_TYPES: List<String> = listOf("task", "bug", "chore")

    /** The status of work nobody has started. */
    const val TODO: String = "todo"
}

/** What one row records about who wrote it: the person it was written for, and the agent that acted. */
internal data class WhoWroteIt(
    val createdBy: String?,
    val updatedBy: String?,
    val createdByAgent: String?,
    val updatedByAgent: String?,
)

/** A person acting directly, with no agent between them and the write. */
internal fun byThemselves(person: String): WhoWroteIt = WhoWroteIt(person, person, "", "")

/** A person whose work [agent] did, which is every write that arrives over a connection. */
internal fun through(person: String, agent: String): WhoWroteIt = WhoWroteIt(person, person, agent, agent)

/** Who this reply says wrote the entity it carries. */
internal fun JsonObject.whoWroteIt(): WhoWroteIt = WhoWroteIt(
    createdBy = text("createdBy"),
    updatedBy = text("updatedBy"),
    createdByAgent = text("createdByAgent"),
    updatedByAgent = text("updatedByAgent"),
)

/**
 * What [field] holds, where it holds text — and nothing at all where the field
 * is absent or holds nothing, which are two ways of saying the same thing here.
 *
 * The library reads a JSON null back as the four characters `null`, so it is
 * ruled out: a check pointed at a field that turned out empty must fail on the
 * emptiness rather than carry the word around as if it were a value.
 */
internal fun JsonObject.text(field: String): String? =
    (this[field] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/** The handle every row in [table] carries, against who the database says wrote it. */
internal fun whoWroteEachRowOf(jdbcUrl: String, table: String, project: UUID): Map<String, WhoWroteIt> = rowsFrom(
    jdbcUrl,
    "select slug, created_by, updated_by, created_by_agent, updated_by_agent from $table where project_id = ?",
    project,
).associate { row ->
    row["slug"] as String to WhoWroteIt(
        createdBy = row["created_by"] as String?,
        updatedBy = row["updated_by"] as String?,
        createdByAgent = row["created_by_agent"] as String?,
        updatedByAgent = row["updated_by_agent"] as String?,
    )
}

/** Who the database says wrote the project row itself, and whose project it is. */
internal fun projectRow(jdbcUrl: String, project: UUID): Pair<WhoWroteIt, String?> {
    val row = rowsFrom(
        jdbcUrl,
        "select owner_subject, created_by, updated_by, created_by_agent, updated_by_agent from project where id = ?",
        project,
    ).single()
    return WhoWroteIt(
        createdBy = row["created_by"] as String?,
        updatedBy = row["updated_by"] as String?,
        createdByAgent = row["created_by_agent"] as String?,
        updatedByAgent = row["updated_by_agent"] as String?,
    ) to row["owner_subject"] as String?
}

/** The blockers the database records against [item], as the ids they are held by. */
internal fun blockersOf(jdbcUrl: String, item: UUID): List<UUID> =
    rowsFrom(jdbcUrl, "select depends_on_id from item_dependency where item_id = ?", item)
        .map { it["depends_on_id"] as UUID }
