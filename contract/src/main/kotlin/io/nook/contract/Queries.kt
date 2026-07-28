package io.nook.contract

import kotlinx.serialization.Serializable

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
 *
 * A part that is not being filtered on is absent from the wire, while a part
 * with no values is an empty list — so the two stay apart across the connection
 * without anything being written by hand for them.
 */
@Serializable
public data class ItemFilter(
    @Describes("Match items of any of these types: epic, task, bug, or chore. Left out, type is not filtered on.")
    public val types: List<String>? = null,
    @Describes(
        "Match items in any of these statuses: todo, in_progress, done, or cancelled. " +
            "Left out, status is not filtered on.",
    )
    public val statuses: List<String>? = null,
    /** Matches an item's own direct parent, never an ancestor further up. */
    @Describes(
        "Match items sitting directly under any of these epics, each by its id or its handle; " +
            "nothing in the list means an item with no epic above it. Left out, where an item sits is not filtered on.",
    )
    public val parents: List<ParentFilter>? = null,
    /** Only an epic carries a release assignment, so a leaf matches no release value. */
    @Describes(
        "Match items assigned to any of these releases, each by its id or its handle. " +
            "Left out, the release is not filtered on.",
    )
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
    @Describes(
        "True for items an unfinished blocker is holding up, false for items nothing is holding up. " +
            "Left out, this is not filtered on.",
    )
    public val heldUp: Boolean? = null,
)

/** One value of the parent part: a named epic, or the reserved "no epic at all". */
@Serializable(with = ParentFilterSerializer::class)
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
