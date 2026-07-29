package io.nook.core.db

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import liquibase.resource.ClassLoaderResourceAccessor

class MigrationTest {

    private companion object {
        /** Where the changelog's own files sit once the build has packaged them. */
        const val CHANGES = "db/changelog/changes"

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
     *
     * Read through Liquibase's own reading of the classpath, which is what
     * [migrateDatabase] applies the changelog with, so the two see the same
     * files. Listing a directory would see them only where the build happens to
     * leave them lying in one — this module ships them inside its jar.
     */
    private fun declaredChangesetIds(): Set<String> = ClassLoaderResourceAccessor().use { packaged ->
        val files = packaged.search(CHANGES, true).filter { it.path.endsWith(".yaml") }
        check(files.isNotEmpty()) {
            "the changelog is not on the test classpath at $CHANGES; processResources is what packages it"
        }
        files.flatMap { file ->
            val declared = file.openInputStream().use { it.reader().readText() }
            CHANGESET_ID.findAll(declared).map { it.groupValues[1] }
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
