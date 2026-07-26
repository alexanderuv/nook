package io.nook.core.read

import io.nook.core.store.requireNoOpenTransaction
import java.sql.Connection
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

// The transaction discipline of the read path: one fresh transaction per read,
// read-only, at repeatable read, and never silently re-executed.
//
// Read-only makes "a read changes nothing" something the database enforces
// rather than something this code remembers. That matters more than usual here,
// because the readiness view is declared as though it were a table and would
// otherwise accept a write straight through into the items underneath it.
//
// Repeatable read is what lets an answer assembled from more than one statement
// describe one moment. At PostgreSQL's normal setting each statement takes a
// fresh snapshot, so a listing and the blocker sets belonging to it can straddle
// somebody else's commit and report a state that never existed.
//
// No lock is taken. Locking is a write discipline — writers in one scope taking
// turns — and a read-only transaction reading a single moment needs nothing from
// it.
//
// None of the above survives nesting, so nesting is refused rather than allowed
// to look like it worked. Asked for a transaction while one is already open,
// Exposed hands back the open one: the isolation level and the read-only flag
// are read from the outer transaction and the ones asked for here are dropped
// on the floor, silently. Every guarantee this file exists to make would then
// depend on whoever opened the outer transaction, which is exactly the sort of
// thing that holds until the day it doesn't.

internal fun <T> readTransaction(db: Database, block: JdbcTransaction.() -> T): T {
    requireNoOpenTransaction("a read")
    return transaction(db, Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
        maxAttempts = 1
        block()
    }
}
