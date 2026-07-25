package io.nook.contract

/**
 * The filter of an item listing: four independent parts, each of them
 * optional. Parts narrow each other — an item is returned only when it matches
 * every part supplied — while the values inside a part widen it, matching any
 * one of them.
 *
 * A null part is how a caller says "don't filter on this". An empty list is
 * something else entirely: asking to match any of nothing, which the service
 * rejects rather than answering with a silent empty result.
 *
 * There is deliberately nothing here that asks for deleted rows. A listing
 * considers live rows only, and no value, part, or combination reaches past
 * that.
 */
data class ItemFilter(
    /** Item types by label: `epic`, `task`, `bug`, `chore`. */
    val types: List<String>? = null,
    /** Item statuses by label: `todo`, `in_progress`, `done`, `cancelled`. */
    val statuses: List<String>? = null,
    /** Matches an item's own direct parent, never an ancestor further up. */
    val parents: List<ParentFilter>? = null,
    /**
     * Release references, matching an item's own release assignment. Only an
     * epic carries one, so a leaf matches no release value.
     */
    val releases: List<String>? = null,
)

/** One value of the parent part: a named epic, or the reserved "no epic at all". */
sealed interface ParentFilter {

    /** Items hanging directly off the project, with no epic above them. */
    data object NoEpic : ParentFilter

    /** Items whose direct parent is the epic named by [ref]. */
    data class Epic(val ref: String) : ParentFilter
}
