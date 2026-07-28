package io.nook.contract

import kotlinx.serialization.Serializable

// Enum-valued columns are stored as smallint codes. Each member carries its
// integer explicitly — never the ordinal — so reordering members can never
// remap rows already in the database. `label` is the string callers use for
// the field ("task", "in_progress"); lookups by either form return null for
// anything outside the vocabulary, and the caller decides how to fail.
//
// The label is also what crosses the wire, which is what the serializer named
// on each vocabulary below is for: neither the code nor the Kotlin name is a
// caller's business. The lookup by label lives there as well and `fromLabel`
// reads it, so a word means the same member at the wire and at the services
// rather than in two lookups free to answer differently.

/**
 * A vocabulary member under the word a caller writes for it.
 *
 * The word is the whole of what crosses the wire and the whole of what the
 * services validate against, so it is stated once here, beside the member, in
 * the only place a member can be added.
 */
public interface Labelled {
    public val label: String
}

/** Work-item kinds: an epic is a container; task, bug, and chore are leaves. */
@Serializable(with = ItemTypeSerializer::class)
public enum class ItemType(public val code: Short, override val label: String) : Labelled {
    EPIC(1, "epic"),
    TASK(2, "task"),
    BUG(3, "bug"),
    CHORE(4, "chore");

    public val isLeaf: Boolean get() = this != EPIC

    public companion object {
        public fun fromCode(code: Short): ItemType? = entries.firstOrNull { it.code == code }
        public fun fromLabel(label: String): ItemType? = ItemTypeSerializer.of(label)
    }
}

/** The status vocabulary of a work item. Any member may move to any member. */
@Serializable(with = ItemStatusSerializer::class)
public enum class ItemStatus(public val code: Short, override val label: String) : Labelled {
    TODO(1, "todo"),
    IN_PROGRESS(2, "in_progress"),
    DONE(3, "done"),
    CANCELLED(4, "cancelled");

    public companion object {
        public fun fromCode(code: Short): ItemStatus? = entries.firstOrNull { it.code == code }
        public fun fromLabel(label: String): ItemStatus? = ItemStatusSerializer.of(label)
    }
}

/** The status vocabulary of a release. Any member may move to any member. */
@Serializable(with = ReleaseStatusSerializer::class)
public enum class ReleaseStatus(public val code: Short, override val label: String) : Labelled {
    PLANNED(1, "planned"),
    IN_PROGRESS(2, "in_progress"),
    RELEASED(3, "released"),
    CANCELLED(4, "cancelled");

    public companion object {
        public fun fromCode(code: Short): ReleaseStatus? = entries.firstOrNull { it.code == code }
        public fun fromLabel(label: String): ReleaseStatus? = ReleaseStatusSerializer.of(label)
    }
}
