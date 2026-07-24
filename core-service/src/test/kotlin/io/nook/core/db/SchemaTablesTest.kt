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
            allStructureTables.forEach { table ->
                assertEquals(0L, table.selectAll().count(), "expected ${table.tableName} to exist and be empty")
            }
        }
    }
}
