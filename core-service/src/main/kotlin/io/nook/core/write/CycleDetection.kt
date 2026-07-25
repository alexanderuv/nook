package io.nook.core.write

import kotlin.uuid.Uuid

/**
 * Decides whether replacing [item]'s blocker set with [newBlockers] would
 * close a loop in the dependency graph. [blockersByItem] maps each item to
 * the set of items it is blocked by; the walk substitutes [newBlockers] for
 * [item]'s current entry and asks whether [item] can be reached from any of
 * its would-be blockers by following blocker edges. Pure function — the
 * caller loads the edges inside its locked transaction, which is what makes
 * the answer trustworthy under concurrent writers.
 */
internal fun wouldCreateCycle(
    blockersByItem: Map<Uuid, Set<Uuid>>,
    item: Uuid,
    newBlockers: Set<Uuid>,
): Boolean {
    val edges = blockersByItem + (item to newBlockers)
    val visited = mutableSetOf<Uuid>()
    val pending = ArrayDeque(newBlockers)
    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        if (current == item) return true
        if (visited.add(current)) pending.addAll(edges[current].orEmpty())
    }
    return false
}
