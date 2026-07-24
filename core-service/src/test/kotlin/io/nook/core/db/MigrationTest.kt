package io.nook.core.db

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationTest {

    @Test
    fun `changelog applies fully and a second run is a no-op`() {
        val url = EmbeddedPostgresSupport.freshMigratedDatabase()
        DriverManager.getConnection(url).use { connection ->
            println("PostgreSQL build: ${serverVersion(connection)}")
            assertEquals(8, appliedChangesetCount(connection))
        }

        migrateDatabase(url)

        DriverManager.getConnection(url).use { connection ->
            assertEquals(8, appliedChangesetCount(connection))
        }
    }

    private fun appliedChangesetCount(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM databasechangelog").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun serverVersion(connection: Connection): String =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version()").use { rows ->
                rows.next()
                rows.getString(1)
            }
        }
}
