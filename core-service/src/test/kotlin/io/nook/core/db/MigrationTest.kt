package io.nook.core.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationTest {

    private companion object {
        /**
         * A changeset id as the changelog writes one: `id:` opening a line. Column
         * names sit inside inline maps (`{name: id, ...}`) and never start a line,
         * so nothing else in these files can match.
         */
        val CHANGESET_ID = Regex("""^\s*id:\s*(\S+)""", RegexOption.MULTILINE)
    }

    @Test
    fun `changelog applies fully and a second run is a no-op`() {
        val url = EmbeddedPostgresSupport.freshMigratedDatabase()
        val declared = declaredChangesetIds()
        DriverManager.getConnection(url).use { connection ->
            println("PostgreSQL build: ${serverVersion(connection)}")
            assertEquals(declared, appliedChangesetIds(connection))
        }

        migrateDatabase(url)

        DriverManager.getConnection(url).use { connection ->
            assertEquals(declared, appliedChangesetIds(connection))
        }
    }

    /**
     * Every changeset the changelog declares, read from the packaged changelog
     * itself rather than counted by hand. A hand-kept number says only how many
     * rows appeared, never which ones: a changeset that silently failed to apply
     * while another was added would leave the count right and the schema wrong.
     */
    private fun declaredChangesetIds(): Set<String> {
        val changes = checkNotNull(javaClass.classLoader.getResource("db/changelog/changes")) {
            "the changelog is not on the test classpath; processResources is what packages it"
        }
        val files = File(changes.toURI()).listFiles { file: File -> file.extension == "yaml" }
        check(!files.isNullOrEmpty()) { "no changelog files found at $changes" }
        return files.flatMap { file ->
            CHANGESET_ID.findAll(file.readText()).map { it.groupValues[1] }
        }.toSet()
    }

    private fun appliedChangesetIds(connection: Connection): Set<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id FROM databasechangelog").use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1)) }
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
