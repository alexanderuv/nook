package io.nook.core.db

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The two halves of soft delete that only PostgreSQL itself can confirm, read
 * back out of the migrated database.
 *
 * Neither is covered by the drift guard. That guard compares which rules exist,
 * not which rows they cover, so it stays green the day a handle index is
 * rebuilt without its condition; and it does not recognise a view at all. These
 * assertions are what actually hold the schema change in place.
 */
class SoftDeleteSchemaTest {

    private companion object {
        val url by lazy { EmbeddedPostgresSupport.freshMigratedDatabase() }

        /** Every rule that must apply among live rows only, with the columns it covers. */
        val handleRules = mapOf(
            "uq_project_slug" to listOf("slug"),
            "uq_release_project_slug" to listOf("project_id", "slug"),
            "uq_item_project_slug" to listOf("project_id", "slug"),
        )
    }

    @Test
    fun `every handle rule is a unique index limited to live rows`() {
        withConnection { connection ->
            handleRules.forEach { (rule, columns) ->
                val definition = indexDefinition(connection, rule)
                assertNotNull(definition, "expected an index named $rule")
                val normalized = definition.normalizedSql()
                assertTrue(
                    normalized.startsWith("create unique index $rule "),
                    "$rule must still be unique, got: $definition",
                )
                assertTrue(
                    normalized.endsWith("where (deleted_at is null)"),
                    "$rule must cover live rows only, got: $definition",
                )
                assertTrue(
                    normalized.contains("(${columns.joinToString(", ")})"),
                    "$rule must still cover ${columns.joinToString(", ")}, got: $definition",
                )
            }
        }
    }

    @Test
    fun `no handle rule survives as a plain constraint covering every row`() {
        withConnection { connection ->
            handleRules.keys.forEach { rule ->
                assertEquals(
                    0,
                    count(
                        connection,
                        "SELECT COUNT(*) FROM pg_constraint WHERE conname = '$rule' AND contype = 'u'",
                    ),
                    "$rule must be an index, not a constraint: a constraint covers deleted rows too",
                )
            }
        }
    }

    @Test
    fun `the readiness view reads the deleted mark for the item and for its blockers`() {
        withConnection { connection ->
            val definition = viewDefinition(connection, "ready_item").normalizedSql()
            // PostgreSQL stores the view with its own qualification: the blocker
            // keeps its alias because the subquery joins two tables, while the
            // item's own read loses the redundant one in the single-table outer
            // query. Both forms of the item's read are accepted; the blocker's
            // is not optional.
            val markReads = Regex("""(\w+\.)?deleted_at is null""").findAll(definition).map { it.value }.toList()
            assertTrue(
                markReads.any { it == "deleted_at is null" || it == "i.deleted_at is null" },
                "the view must skip deleted items, got: $definition",
            )
            assertTrue(
                "b.deleted_at is null" in markReads,
                "the view must count a deleted blocker as resolved, got: $definition",
            )
        }
    }

    /** Lowercased with every run of whitespace collapsed, so formatting cannot break a match. */
    private fun String.normalizedSql(): String =
        lowercase().replace(Regex("\\s+"), " ").trim()

    private fun indexDefinition(connection: Connection, indexName: String): String? =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT indexdef FROM pg_indexes WHERE indexname = '$indexName'").use { rows ->
                if (rows.next()) rows.getString(1) else null
            }
        }

    private fun viewDefinition(connection: Connection, viewName: String): String =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_get_viewdef('$viewName', true)").use { rows ->
                assertTrue(rows.next(), "expected a view named $viewName")
                rows.getString(1)
            }
        }

    private fun count(connection: Connection, sql: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun <T> withConnection(block: (Connection) -> T): T =
        DriverManager.getConnection(url).use(block)
}
