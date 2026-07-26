package io.nook.core.db

import io.nook.core.read.readTransaction
import io.nook.core.write.writeTransaction
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Neither transaction discipline may be entered from inside another.
 *
 * This is the failure mode with no symptom. Asked for a transaction while one is
 * already open on the thread, Exposed hands back the open one — nested
 * transactions are off by default — and quietly drops the isolation level and
 * the read-only flag the inner call asked for. The inner block then runs
 * believing it has guarantees belonging to whoever opened the outer transaction:
 * a read believing it is read-only at repeatable read, a write believing its
 * lock is held for a commit boundary it controls.
 *
 * Nothing on either service's surface is reachable this way today, which is
 * exactly why the check matters — the composition that breaks it is the one
 * somebody writes later, on a layer that calls both.
 */
class TransactionNestingTest {

    private val db = Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase())

    private fun assertRefusesNesting(block: () -> Unit) {
        val failure = assertFailsWith<IllegalStateException>(block = block)
        assertTrue(
            failure.message.orEmpty().contains("inside another transaction"),
            "the refusal must say what went wrong, got: ${failure.message}",
        )
    }

    @Test
    fun `a read transaction inside any open transaction is refused`() {
        assertRefusesNesting { transaction(db) { readTransaction(db) { } } }
    }

    @Test
    fun `a read transaction inside another read transaction is refused`() {
        assertRefusesNesting { readTransaction(db) { readTransaction(db) { } } }
    }

    @Test
    fun `a write transaction inside any open transaction is refused`() {
        assertRefusesNesting { transaction(db) { writeTransaction(db) { } } }
    }

    @Test
    fun `a write transaction inside a read transaction is refused`() {
        assertRefusesNesting { readTransaction(db) { writeTransaction(db) { } } }
    }

    @Test
    fun `each discipline still opens normally on a thread holding nothing`() {
        readTransaction(db) { }
        writeTransaction(db) { }
    }
}
