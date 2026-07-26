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
 * There is deliberately nothing here that asks for deleted rows, and nothing
 * that could: deleting removes the row, so a listing has nothing to reach past
 * in the first place.
 */
public data class ItemFilter(
    /** Item types by label: `epic`, `task`, `bug`, `chore`. */
    public val types: List<String>? = null,
    /** Item statuses by label: `todo`, `in_progress`, `done`, `cancelled`. */
    public val statuses: List<String>? = null,
    /** Matches an item's own direct parent, never an ancestor further up. */
    public val parents: List<ParentFilter>? = null,
    /**
     * Release references, matching an item's own release assignment. Only an
     * epic carries one, so a leaf matches no release value.
     */
    public val releases: List<String>? = null,
)

/** One value of the parent part: a named epic, or the reserved "no epic at all". */
public sealed interface ParentFilter {

    /**
     * Items with no epic above them — the leaves sitting directly on the
     * project, and the project's epics, which never have a parent either.
     *
     * The epics are not an accident of the implementation. Parts of a filter
     * narrow each other, so `NoEpic` together with a type of `epic` has to
     * return the project's epics; excluding them here would make that
     * combination empty and leave "top-level epics" unaskable.
     */
    public data object NoEpic : ParentFilter

    /** Items whose direct parent is the epic named by [ref]. */
    public data class Epic(public val ref: String) : ParentFilter
}
