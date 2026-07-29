package io.nook.core.db

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import java.nio.file.Path

/** The role and the database the server is reached as, which are the ones it is created with. */
private const val SUPERUSER = "postgres"

/**
 * An embedded PostgreSQL a caller owns outright: one server on a port it chose
 * and a data directory it keeps, so the database can be taken away and brought
 * back at the same address without anything that was written in it being lost.
 *
 * Stopping is closing the server and starting is building the same one again —
 * the library has no restart of its own and needs none, because it creates a
 * database only when the data directory holds none yet.
 *
 * The changelog is applied on every start rather than on the first: what a
 * caller is handed is then a migrated database whatever has happened to it,
 * without a flag anywhere recording which start this was. Already-applied
 * changesets are skipped, so a second start pays for the check and nothing more.
 */
class RestartablePostgres(private val port: Int) : AutoCloseable {

    private val dataDirectory: Path = Files.createTempDirectory("nook-restartable-postgres")

    /** The server, where one is running — which is shorter-lived than this, by the whole point of it. */
    private var server: EmbeddedPostgres? = null

    /** Where this answers, which is the same address across every stop and start. */
    val jdbcUrl: String

    init {
        val first = startServer()
        server = first
        jdbcUrl = first.getJdbcUrl(SUPERUSER, SUPERUSER)
        migrateDatabase(jdbcUrl)
    }

    /** Brings the database back at the same address, holding everything it held before. */
    fun start() {
        check(server == null) { "this database is already answering at $jdbcUrl" }
        server = startServer()
        migrateDatabase(jdbcUrl)
    }

    /** Takes the database away, keeping what is in it. Doing it twice is doing it once. */
    fun stop() {
        server?.close()
        server = null
    }

    /** Takes the database away for good, and the directory with it. */
    override fun close() {
        stop()
        dataDirectory.toFile().deleteRecursively()
    }

    private fun startServer(): EmbeddedPostgres = EmbeddedPostgres.builder()
        .setPort(port)
        .setDataDirectory(dataDirectory)
        // The whole of what makes this restartable: a directory cleaned on the
        // way out is one the next server would create an empty database in.
        .setCleanDataDirectory(false)
        .start()
}
