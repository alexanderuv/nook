package io.nook.contract

/**
 * The filter of an item listing: five independent parts, each of them
 * optional. Parts narrow each other — an item is returned only when it matches
 * every part supplied — while the values inside a part widen it, matching any
 * one of them.
 *
 * A null part is how a caller says "don't filter on this". An empty list is
 * something else entirely: asking to match any of nothing, which the service
 * rejects rather than answering with a silent empty result.
 *
 * Combining parts is how a caller asks a compound question, and the compound
 * question this milestone exists for is what to work on next: the leaf types,
 * status `todo`, and [heldUp] false. Adding a parent narrows the same question
 * to one epic. There is no readiness operation, because there is nothing left
 * for one to do.
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
    /**
     * Whether anything unfinished is holding the item up: true for an item with
     * a blocker that is neither `done` nor `cancelled`, false for one with no
     * blockers or none left unfinished.
     *
     * A boolean rather than a list, because the question has two answers rather
     * than a set of values — a part asking for both would be the same as not
     * asking.
     *
     * It carries no clause of its own about type or status. An epic is answered
     * by it rather than left out of it — as not held up, since an epic takes no
     * blockers — and a `done` item with an unfinished blocker is held up like
     * any other.
     */
    public val heldUp: Boolean? = null,
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
