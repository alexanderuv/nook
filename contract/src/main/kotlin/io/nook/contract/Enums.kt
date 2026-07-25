package io.nook.contract

// Enum-valued columns are stored as smallint codes. Each member carries its
// integer explicitly — never the ordinal — so reordering members can never
// remap rows already in the database. `label` is the string callers use for
// the field ("task", "in_progress"); lookups by either form return null for
// anything outside the vocabulary, and the caller decides how to fail.

/** Work-item kinds: an epic is a container; task, bug, and chore are leaves. */
enum class ItemType(val code: Short, val label: String) {
    EPIC(1, "epic"),
    TASK(2, "task"),
    BUG(3, "bug"),
    CHORE(4, "chore");

    val isLeaf: Boolean get() = this != EPIC

    companion object {
        fun fromCode(code: Short): ItemType? = entries.firstOrNull { it.code == code }
        fun fromLabel(label: String): ItemType? = entries.firstOrNull { it.label == label }
    }
}

/** The status vocabulary of a work item. Any member may move to any member. */
enum class ItemStatus(val code: Short, val label: String) {
    TODO(1, "todo"),
    IN_PROGRESS(2, "in_progress"),
    DONE(3, "done"),
    CANCELLED(4, "cancelled");

    companion object {
        fun fromCode(code: Short): ItemStatus? = entries.firstOrNull { it.code == code }
        fun fromLabel(label: String): ItemStatus? = entries.firstOrNull { it.label == label }
    }
}

/** The status vocabulary of a release. Any member may move to any member. */
enum class ReleaseStatus(val code: Short, val label: String) {
    PLANNED(1, "planned"),
    IN_PROGRESS(2, "in_progress"),
    RELEASED(3, "released"),
    CANCELLED(4, "cancelled");

    companion object {
        fun fromCode(code: Short): ReleaseStatus? = entries.firstOrNull { it.code == code }
        fun fromLabel(label: String): ReleaseStatus? = entries.firstOrNull { it.label == label }
    }
}
