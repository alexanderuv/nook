package io.nook.core.read

import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.ProjectItemTable
import io.nook.core.db.ProjectTable
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * The read path's transaction discipline, checked where it actually holds: in
 * the database.
 *
 * "A read changes nothing" is not a convention here — a read-only transaction
 * refuses the write itself. That matters most for the readiness view, which is
 * declared as though it were a table and which PostgreSQL would otherwise let
 * code write straight through into the items underneath it.
 */
class ReadTransactionTest {

    private companion object {
        val db by lazy { Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase()) }
        val seededProject: Uuid = Uuid.random()

        val seeded by lazy {
            transaction(db) {
                ProjectTable.insert {
                    it[id] = seededProject
                    it[slug] = "read-only"
                    it[name] = "Read only"
                }
            }
            true
        }
    }

    /** The database's own complaint about a write in a read-only transaction. */
    private fun assertRefusedAsReadOnly(attempt: () -> Unit) {
        val failure = runCatching(attempt).exceptionOrNull()
        val reported = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue(
            reported.contains("read-only transaction", ignoreCase = true),
            "expected the database to refuse the write, got: ${failure ?: "no failure at all"}",
        )
    }

    @Test
    fun `a write to a table inside a read transaction is refused by the database`() {
        seeded
        assertRefusedAsReadOnly {
            readTransaction(db) {
                ProjectTable.update({ ProjectTable.id eq seededProject }) {
                    it[ProjectTable.name] = "Renamed by a read"
                }
            }
        }
        assertEquals(
            "Read only",
            transaction(db) {
                ProjectTable.selectAll().where { ProjectTable.id eq seededProject }.first()[ProjectTable.name]
            },
        )
    }

    @Test
    fun `a write through the readiness view inside a read transaction is refused by the database`() {
        seeded
        assertRefusedAsReadOnly {
            readTransaction(db) {
                ReadyItemView.insert {
                    it[ReadyItemView.id] = Uuid.random()
                    it[ReadyItemView.projectId] = seededProject
                    it[ReadyItemView.type] = 2
                    it[ReadyItemView.slug] = "smuggled"
                    it[ReadyItemView.name] = "Smuggled through the view"
                    it[ReadyItemView.status] = 1
                    it[ReadyItemView.createdAt] = Instant.now()
                    it[ReadyItemView.updatedAt] = Instant.now()
                    it[ReadyItemView.createdBy] = "system"
                    it[ReadyItemView.updatedBy] = "system"
                }
            }
        }
        assertEquals(
            0L,
            transaction(db) {
                ProjectItemTable.selectAll().where { ProjectItemTable.slug eq "smuggled" }.count()
            },
            "nothing may reach project_item through the view",
        )
    }

    @Test
    fun `the same write outside a read transaction succeeds, so the refusal is the transaction's doing`() {
        seeded
        val smuggled = Uuid.random()
        transaction(db) {
            ReadyItemView.insert {
                it[ReadyItemView.id] = smuggled
                it[ReadyItemView.projectId] = seededProject
                it[ReadyItemView.type] = 2
                it[ReadyItemView.slug] = "writable-view"
                it[ReadyItemView.name] = "The view is writable"
                it[ReadyItemView.status] = 1
                it[ReadyItemView.createdAt] = Instant.now()
                it[ReadyItemView.updatedAt] = Instant.now()
                it[ReadyItemView.createdBy] = "system"
                it[ReadyItemView.updatedBy] = "system"
            }
        }
        assertEquals(
            1L,
            transaction(db) {
                ProjectItemTable.selectAll().where { ProjectItemTable.id eq smuggled }.count()
            },
            "the view passes writes through: only the read-only transaction stops them",
        )
    }
}
