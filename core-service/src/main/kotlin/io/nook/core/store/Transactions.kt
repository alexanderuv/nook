package io.nook.core.store

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

// The one thing both transaction disciplines need from each other: neither may
// be entered from inside the other, or from inside itself.
//
// Asked to open a transaction while one is already open on this thread, Exposed
// hands back the open one rather than nesting — nested transactions are off by
// default — and the isolation level and read-only flag passed at the inner call
// are discarded in favour of the outer transaction's. Nothing reports this. The
// inner block then runs believing it has guarantees it does not have: a read
// believing it is read-only at repeatable read, a write believing it holds a
// lock taken in a transaction that will commit when it says so.
//
// So the check is not a tidiness rule. It is the only thing standing between a
// future caller who composes two operations and a discipline that quietly
// stops holding.

/**
 * Fails unless this thread has no transaction open, naming [what] is being
 * opened so the message says which call to move out.
 *
 * A fault in this service rather than a caller error: no caller can reach a
 * nested transaction, because nothing on either service's surface takes one.
 */
internal fun requireNoOpenTransaction(what: String) {
    check(TransactionManager.currentOrNull() == null) {
        "$what transaction was opened inside another transaction, which Exposed would " +
            "silently satisfy with the open one — discarding the isolation level, the " +
            "read-only flag, and the commit boundary this discipline depends on"
    }
}
