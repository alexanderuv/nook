package io.nook.core.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import kotlin.test.Test
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
