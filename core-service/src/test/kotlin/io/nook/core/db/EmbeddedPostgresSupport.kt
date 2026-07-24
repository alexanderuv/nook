package io.nook.core.db

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.atomic.AtomicInteger

/**
 * One embedded PostgreSQL server for the whole test JVM; each caller gets its
 * own freshly created, fully migrated database on it. The binaries are real
 * PostgreSQL, fetched as ordinary dependencies and started from a temp
 * directory — no Docker, no installed database.
 */
object EmbeddedPostgresSupport {
    private val databaseCounter = AtomicInteger()

    private val server: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }

    /** Creates a new empty database, runs the changelog on it, and returns its JDBC URL. */
    fun freshMigratedDatabase(): String {
        val name = "nook_test_${databaseCounter.incrementAndGet()}"
        server.postgresDatabase.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        val url = server.getJdbcUrl("postgres", name)
        migrateDatabase(url)
        return url
    }
}
