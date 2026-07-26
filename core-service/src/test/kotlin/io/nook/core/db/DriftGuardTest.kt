package io.nook.core.db

import java.sql.Connection
import java.sql.DriverManager
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The drift guard: the Exposed table declarations must mirror the
 * Liquibase-built schema exactly. [MigrationUtils] returns the SQL that would
 * reconcile the declarations with the live database — for an exact mirror,
 * nothing must remain after filtering the statements the comparison cannot
 * avoid emitting (computed defaults re-stated even when the database already
 * has them, because expression equality is invisible to it).
 *
 * The filter is a tripwire, not a wastebasket: it accepts only that one known
 * shape. Any other statement is a real mismatch — fix the declaration (or, for
 * an intended schema change, add a changeset), never the filter.
 */
class DriftGuardTest {

    @Test
    fun `declared tables match the migrated schema`() {
        val database = Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase())
        val drift = transaction(database) {
            MigrationUtils.statementsRequiredForDatabaseMigration(*allStructureTables, withLogs = false)
        }.filterNot(::isComputedDefaultRestatement)
        assertTrue(drift.isEmpty(), "schema drift detected:\n${drift.joinToString("\n")}")
    }

    @Test
    fun `the guard catches a declaration the schema does not have`() {
        val database = Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase())
        val statements = transaction(database) {
            MigrationUtils.statementsRequiredForDatabaseMigration(DriftedProjectTable, withLogs = false)
        }
        assertTrue(
            statements.any { it.contains("color", ignoreCase = true) },
            "expected the undeclared column to be flagged, got:\n${statements.joinToString("\n")}",
        )
    }

    /**
     * What the guard above cannot do, and the shape of the answer.
     *
     * The comparison reports which rules exist, not what each one says. It sees
     * a missing index and a missing constraint; it does not see an index
     * rebuilt without its condition, a CHECK whose predicate was replaced under
     * the same name, or a column retyped in a way it treats as equivalent —
     * `timestamp` for `timestamptz` among them, which is how a timezone bug can
     * pass a drift check.
     *
     * So each of these builds the schema a second way — from the declarations,
     * onto an empty database — and compares what PostgreSQL itself reports about
     * the two. A catalog reading is the definition, not a summary of it, so
     * anything the two schemas disagree about shows up whether or not the
     * comparison above has a name for it.
     */
    @Test
    fun `the indexes the declarations build match the migrated schema's`() {
        val fromDeclarations = indexDefinitions(schemaBuiltFromDeclarations())
        val fromChangelog = indexDefinitions(EmbeddedPostgresSupport.freshMigratedDatabase())

        assertEquals(fromChangelog, fromDeclarations)
        assertTrue(
            fromDeclarations.keys.containsAll(
                listOf("uq_project_slug", "uq_release_project_slug", "uq_item_project_slug"),
            ),
            "expected the three handle rules among ${fromDeclarations.keys}",
        )
    }

    @Test
    fun `the columns the declarations build match the migrated schema's, type for type`() {
        val fromDeclarations = columnDefinitions(schemaBuiltFromDeclarations())
        val fromChangelog = columnDefinitions(EmbeddedPostgresSupport.freshMigratedDatabase())

        assertEquals(fromChangelog, fromDeclarations)
    }

    /**
     * Every audit timestamp keeps its zone. Stated separately from the
     * comparison above because it is the one column property nothing else in
     * this file would notice losing: both halves would have to be wrong
     * together, which is exactly what a careless half-migration produces.
     */
    @Test
    fun `every timestamp column carries a time zone`() {
        val columns = columnDefinitions(EmbeddedPostgresSupport.freshMigratedDatabase())
        val timestamps = columns.filterKeys { it.endsWith(".created_at") || it.endsWith(".updated_at") }

        assertEquals(9, timestamps.size, "expected nine audit timestamps, found ${timestamps.keys}")
        timestamps.forEach { (column, definition) ->
            assertTrue(
                definition.startsWith("timestamp with time zone"),
                "$column is $definition, so the moment it names depends on who reads it",
            )
        }
    }

    @Test
    fun `the constraints the declarations build match the migrated schema's, predicate included`() {
        val fromDeclarations = constraintDefinitions(schemaBuiltFromDeclarations())
        val fromChangelog = constraintDefinitions(EmbeddedPostgresSupport.freshMigratedDatabase())

        assertEquals(fromChangelog, fromDeclarations)
        assertEquals(
            "CHECK ((item_id <> depends_on_id))",
            fromChangelog["ck_dep_no_self_block"],
            "the schema's only business rule must still say what it always said",
        )
    }

    /** An empty database with the schema built from the Exposed declarations alone. */
    private fun schemaBuiltFromDeclarations(): String {
        val url = EmbeddedPostgresSupport.freshEmptyDatabase()
        transaction(Database.connect(url)) {
            SchemaUtils.create(tables = allStructureTables, inBatch = false)
        }
        return url
    }

    /** Every index PostgreSQL holds for the structure tables, by name, as it renders them. */
    private fun indexDefinitions(jdbcUrl: String): Map<String, String> = catalogReading(
        jdbcUrl,
        "SELECT indexname, indexdef FROM pg_indexes WHERE tablename IN (${structureTableNames()})",
    )

    /**
     * Every structure column, as `table.column` to the type and nullability
     * PostgreSQL reports. The type is spelled out in full — `timestamp with
     * time zone` and `timestamp without time zone` are different readings here,
     * which is the whole reason for asking the catalog rather than the guard.
     */
    private fun columnDefinitions(jdbcUrl: String): Map<String, String> = catalogReading(
        jdbcUrl,
        """
        SELECT table_name || '.' || column_name,
               data_type
                 || coalesce(' (' || character_maximum_length || ')', '')
                 || coalesce(' (' || numeric_precision || ')', '')
                 || ' ' || is_nullable
          FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name IN (${structureTableNames()})
        """.trimIndent(),
    )

    /** Every constraint on the structure tables, by name, as PostgreSQL defines it. */
    private fun constraintDefinitions(jdbcUrl: String): Map<String, String> = catalogReading(
        jdbcUrl,
        """
        SELECT c.conname, pg_get_constraintdef(c.oid)
          FROM pg_constraint c
          JOIN pg_class t ON t.oid = c.conrelid
         WHERE t.relname IN (${structureTableNames()})
        """.trimIndent(),
    )

    private fun structureTableNames(): String = allStructureTables.joinToString { "'${it.tableName}'" }

    private fun catalogReading(jdbcUrl: String, query: String): Map<String, String> =
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(query).use { rows ->
                    buildMap {
                        while (rows.next()) put(rows.getString(1), rows.getString(2))
                    }
                }
            }
        }

    // The comparison re-emits computed defaults verbatim even when the database
    // already has them; this is the only statement shape the guard tolerates.
    private val computedDefaultRestatement =
        Regex("""ALTER TABLE \S+ ALTER COLUMN \S+ SET DEFAULT CURRENT_TIMESTAMP""")

    private fun isComputedDefaultRestatement(statement: String): Boolean =
        computedDefaultRestatement.matches(statement.trim())
}

/** The project table plus one column the changelog never created — guaranteed drift. */
private object DriftedProjectTable : Table("project") {
    val id = uuid("id")
    val color = varchar("color", 20)

    override val primaryKey = PrimaryKey(id)
}
