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
     * What the guard above cannot do. It compares which rules exist, not which
     * rows they cover, so a handle index rebuilt without its live-only condition
     * leaves it green. Building the schema from the declarations onto an empty
     * database and reading both results back out of PostgreSQL compares the
     * whole index definition, condition included.
     */
    @Test
    fun `the indexes the declarations build match the migrated schema's`() {
        val declared = EmbeddedPostgresSupport.freshEmptyDatabase()
        transaction(Database.connect(declared)) {
            SchemaUtils.create(tables = allStructureTables, inBatch = false)
        }

        val fromDeclarations = indexDefinitions(declared)
        val fromChangelog = indexDefinitions(EmbeddedPostgresSupport.freshMigratedDatabase())

        assertEquals(fromChangelog, fromDeclarations)
        assertTrue(
            fromDeclarations.keys.containsAll(
                listOf("uq_project_slug", "uq_release_project_slug", "uq_item_project_slug"),
            ),
            "expected the three handle rules among ${fromDeclarations.keys}",
        )
    }

    /** Every index PostgreSQL holds for the structure tables, by name, as it renders them. */
    private fun indexDefinitions(jdbcUrl: String): Map<String, String> {
        val tableNames = allStructureTables.joinToString { "'${it.tableName}'" }
        return DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT indexname, indexdef FROM pg_indexes WHERE tablename IN ($tableNames)",
                ).use { rows ->
                    buildMap {
                        while (rows.next()) put(rows.getString(1), rows.getString(2))
                    }
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
