package io.nook.core.write

import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

// The transaction discipline of the write path. Every write runs in one fresh
// transaction that never silently re-executes (Exposed's default re-runs a
// failed block up to three times — a hazard for code that must not run twice),
// and takes a PostgreSQL advisory lock before touching anything, so writers in
// the same scope take turns instead of interleaving. The lock is released by
// PostgreSQL automatically when the transaction ends.

// Key for writes whose uniqueness scope is the whole instance (project
// creation). An arbitrary fixed value, distinct in practice from any project's
// hashed key.
internal const val INSTANCE_LOCK_KEY: Long = 0x6E6F6F6B_00000001L

/**
 * The lock key for a project: the two halves of its UUID folded to one long.
 * A collision between two projects' keys is harmless — they would merely take
 * turns with each other unnecessarily.
 */
internal fun projectLockKey(projectId: Uuid): Long =
    projectId.toLongs { mostSignificant, leastSignificant -> mostSignificant xor leastSignificant }

/**
 * Runs [block] in one fresh transaction that fails on the first error instead
 * of silently re-running. The block must take its advisory lock (via
 * [takeProjectLock] or [takeInstanceLock]) before reading anything it will
 * write against.
 *
 * Every caller mistake is refused by validation inside the block, so the store
 * refusing a write means validation let an invalid one through. That is a fault
 * in this service — reported as one, with the store's own complaint attached —
 * and never dressed up as a caller error.
 */
internal fun <T> writeTransaction(db: Database, block: JdbcTransaction.() -> T): T =
    try {
        transaction(db) {
            maxAttempts = 1
            block()
        }
    } catch (rejection: ExposedSQLException) {
        throw IllegalStateException(
            "the store refused a write that validation should have refused first",
            rejection,
        )
    }

/** Blocks until this transaction holds the project's advisory lock. */
internal fun JdbcTransaction.takeProjectLock(projectId: Uuid) {
    exec("SELECT pg_advisory_xact_lock(${projectLockKey(projectId)})")
}

/** Blocks until this transaction holds the instance-wide advisory lock. */
internal fun JdbcTransaction.takeInstanceLock() {
    exec("SELECT pg_advisory_xact_lock($INSTANCE_LOCK_KEY)")
}
