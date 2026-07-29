package io.nook.core.db

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.atomic.AtomicInteger

/**
 * One embedded PostgreSQL server for the whole test JVM; each caller gets its
 * own freshly created, fully migrated database on it. The binaries are real
 * PostgreSQL, fetched as ordinary dependencies and started from a temp
 * directory — no Docker, no installed database.
 *
 * This server is never stopped, and nothing here offers to stop it: it is shared
 * by every caller in the JVM, so taking it away would take all of them with it.
 * A caller that has to take a database away and bring it back builds a
 * [RestartablePostgres] of its own instead.
 */
object EmbeddedPostgresSupport {
    private val databaseCounter = AtomicInteger()

    private val server: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }

    /** Creates a new empty database, runs the changelog on it, and returns its JDBC URL. */
    fun freshMigratedDatabase(): String = freshEmptyDatabase().also(::migrateDatabase)

    /**
     * Creates a new database with no schema at all and returns its JDBC URL —
     * for the one test that builds the schema from the Exposed declarations
     * instead, so the two can be compared.
     */
    fun freshEmptyDatabase(): String {
        val name = "nook_test_${databaseCounter.incrementAndGet()}"
        server.postgresDatabase.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return server.getJdbcUrl("postgres", name)
    }
}
