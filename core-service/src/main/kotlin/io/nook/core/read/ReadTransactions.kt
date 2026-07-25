package io.nook.core.read

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

internal fun <T> readTransaction(db: Database, block: JdbcTransaction.() -> T): T =
    transaction(db, Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
        maxAttempts = 1
        block()
    }
