package io.nook.core.db

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaTablesTest {

    @Test
    fun `every declared table is selectable on the migrated schema`() {
        val database = Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase())
        transaction(database) {
            allStructureTables.filterNot { it == InstanceLockTable }.forEach { table ->
                assertEquals(0L, table.selectAll().count(), "expected ${table.tableName} to exist and be empty")
            }
        }
    }

    @Test
    fun `the migrated schema carries the lock row writers take turns on`() {
        val database = Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase())
        transaction(database) {
            // Seeded by the changelog, not by any write: with no row there is
            // nothing to lock, and project handles lose their turn-taking.
            assertEquals(
                listOf("project_slug"),
                InstanceLockTable.selectAll().map { it[InstanceLockTable.scope] },
            )
        }
    }
}
