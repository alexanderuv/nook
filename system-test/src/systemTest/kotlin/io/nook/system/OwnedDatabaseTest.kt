package io.nook.system

import io.nook.core.db.RestartablePostgres
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The database this run owns, before any program is pointed at it: migrated
 * where it stands, and able to go away and come back at the same address
 * carrying what was written in it.
 *
 * Everything else here rests on that. A core told a store's address is told it
 * once, so a database that came back somewhere else — or came back empty —
 * would have the checks about a store going away measuring a new store instead
 * of the same one returning.
 */
class OwnedDatabaseTest {

    private companion object {
        const val SLUG = "a-project-written-before-the-database-went-away"
    }

    @Test
    fun `a database goes away and comes back at the same address, holding what was written in it`() {
        RestartablePostgres(freePort()).use { database ->
            // The changelog ran: the table is there to be read, and there is
            // nothing in it that this check did not put there.
            assertEquals(emptyList(), slugsIn(database.jdbcUrl), "the run's database started with rows in it")

            runOn(
                database.jdbcUrl,
                "insert into project (id, slug, name) values (?, ?, ?)",
                UUID.randomUUID(),
                SLUG,
                "a project",
            )
            assertEquals(listOf(SLUG), slugsIn(database.jdbcUrl), "the row was not written")

            database.stop()
            assertFailsWith<SQLException>("the database answered at an address it had been taken away from") {
                DriverManager.getConnection(database.jdbcUrl)
            }

            database.start()
            assertEquals(
                listOf(SLUG),
                slugsIn(database.jdbcUrl),
                "the database came back without what had been written in it",
            )
        }
    }

    private fun slugsIn(jdbcUrl: String): List<String?> =
        rowsFrom(jdbcUrl, "select slug from project order by slug").map { it["slug"] as String? }
}
