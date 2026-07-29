package io.nook.core.write

import io.nook.contract.Actor
import io.nook.core.store.validationFailed

// What a row records about who wrote it, read off the identity the call was made
// for. Nothing here interprets either name: the core records exactly the two
// values an adapter told it — nothing trimmed, lowercased, filled in or substituted
// — because an adapter has already refused every shape the store could not hold.

/**
 * The person this call was made for, or a refusal.
 *
 * Every mutation names somebody. A call that names nobody is an adapter with a
 * defect rather than a caller with a mistake, and the refusal is what keeps the
 * store's own fallback value unreachable through the connection: without it,
 * such a call would land on `system` and be indistinguishable afterwards from a
 * row nobody ever attributed.
 *
 * It is a failed validation rather than an internal fault because that is what
 * it is from where the store stands — a call arrived missing something it must
 * carry — and because a fault would say the core broke, which it did not.
 */
internal fun Actor.requirePerson(): String =
    subject ?: validationFailed("this call names no person to record it against")

/**
 * The acting agent as a row holds it: empty text where none acted.
 *
 * Empty rather than absent, because "a person worked directly" is not "which
 * agent is unknown". One value for it means every reader has one case fewer,
 * and it is the same value a client that named itself with nothing produces.
 */
internal val Actor.actingAgent: String get() = agent.orEmpty()
