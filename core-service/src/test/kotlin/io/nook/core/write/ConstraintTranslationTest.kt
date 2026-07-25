package io.nook.core.write

import io.nook.contract.ErrorCode
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.allStructureTables
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * The constraint-name translator against the table declarations: every named
 * rule the schema can throw at the write path must have an error code, of the
 * agreed class. The names come from the declarations themselves; rendering
 * CHECK constraints requires a live SQL dialect, so the enumeration runs
 * inside a transaction on the embedded test database.
 */
class ConstraintTranslationTest {

    private companion object {
        val declaredNames: Set<String> by lazy {
            val db = Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase())
            transaction(db) {
                allStructureTables.flatMap { table ->
                    table.indices.filter { it.unique }.map { it.indexName } +
                        table.foreignKeys.map { it.fkName } +
                        table.checkConstraints().map { it.checkName } +
                        listOfNotNull(table.primaryKey?.name)
                }.toSet()
            }
        }
    }

    @Test
    fun `the enumeration sees all four constraint kinds`() {
        // Guards the coverage test below against silently enumerating nothing.
        assertTrue("uq_item_project_slug" in declaredNames, "unique indexes missing from $declaredNames")
        assertTrue("fk_dep_blocker" in declaredNames, "foreign keys missing from $declaredNames")
        assertTrue("ck_dep_no_self_block" in declaredNames, "check constraints missing from $declaredNames")
        assertTrue("pk_item_dependency" in declaredNames, "primary keys missing from $declaredNames")
    }

    @Test
    fun `every declared constraint has an error translation`() {
        val untranslated = declaredNames - constraintErrorCodes.keys
        assertTrue(untranslated.isEmpty(), "constraints with no error code: $untranslated")
    }

    @Test
    fun `uniqueness rules translate to conflict, reference and check rules to validation_failed`() {
        constraintErrorCodes.forEach { (name, code) ->
            val expected = if (name.startsWith("uq_") || name.startsWith("pk_")) {
                ErrorCode.CONFLICT
            } else {
                ErrorCode.VALIDATION_FAILED
            }
            assertEquals(expected, code, "constraint $name")
        }
    }
}
