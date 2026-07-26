package io.nook.core.write

import io.nook.core.db.InstanceLockTable
import io.nook.core.db.ProjectTable
import io.nook.core.store.notFound
import io.nook.core.store.projectIdentity
import io.nook.core.store.requireNoOpenTransaction
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

// The transaction discipline of the write path. Every write runs in one fresh
// transaction that never silently re-executes (Exposed's default re-runs a
// failed block up to three times — a hazard for code that must not run twice),
// and locks a row before touching anything, so writers in the same scope take
// turns instead of interleaving. The lock is released when the transaction ends.
//
// A locked row, rather than a lock the application names: `SELECT … FOR UPDATE`
// is plain standard SQL, and the schema is deliberately free of engine-specific
// features. It also does more per statement than a named lock could — locking
// the project's own row is simultaneously the check that the project is still
// there, which a lock keyed on an identifier is not.

/** The scope whose contested resource is the instance-wide space of project handles. */
private const val PROJECT_SLUG_SCOPE = "project_slug"

/**
 * Runs [block] in one fresh transaction that fails on the first error instead
 * of silently re-running. The block must take its lock (via [lockProject] or
 * [takeInstanceLock]) before reading anything it will write against.
 *
 * Fresh means fresh: entering this from inside another transaction is refused,
 * because Exposed would satisfy it with the open one and the fresh transaction,
 * its single attempt, and its commit boundary would all be someone else's.
 *
 * Every caller mistake is refused by validation inside the block, so the store
 * refusing a write means validation let an invalid one through. That is a fault
 * in this service — reported as one, with the store's own complaint attached —
 * and never dressed up as a caller error.
 */
internal fun <T> writeTransaction(db: Database, block: JdbcTransaction.() -> T): T {
    requireNoOpenTransaction("a write")
    return try {
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
}

/**
 * Resolves [projectRef], locks the project's row, and returns it — one
 * statement, because with `FOR UPDATE` those are the same act. Blocks until any
 * other writer in this project has finished.
 *
 * A project that is not there, or that another transaction removed while this
 * one waited, comes back as `not_found`: the row is gone by the time the lock
 * would be granted, so the query matches nothing. Resolving first and locking
 * second would leave exactly that gap open.
 */
internal fun lockProject(projectRef: String): ResultRow =
    ProjectTable.selectAll()
        .where(projectIdentity(projectRef))
        .forUpdate(ForUpdateOption.ForUpdate)
        .firstOrNull()
        ?: notFound("no project matches reference \"$projectRef\"")

/**
 * Blocks until this transaction holds the instance-wide turn — the one whose
 * contested resource is the set of project handles, which no project's own row
 * represents.
 *
 * A missing row is a corrupted schema, not an absent requirement: carrying on
 * without the lock would silently forfeit the turn-taking that project handles
 * depend on.
 */
internal fun takeInstanceLock() {
    InstanceLockTable.selectAll()
        .where { InstanceLockTable.scope eq PROJECT_SLUG_SCOPE }
        .forUpdate(ForUpdateOption.ForUpdate)
        .firstOrNull()
        ?: error("the store has no \"$PROJECT_SLUG_SCOPE\" lock row; the schema is incomplete")
}
