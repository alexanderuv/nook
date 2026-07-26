package io.nook.contract

import java.time.LocalDate

/**
 * The write commands: one data class per mutation, carrying that mutation's
 * payload. References that *address* the target (which project, which item)
 * are not part of the payload — operations take them as separate parameters
 * next to the command.
 *
 * Enum-valued fields (item type, statuses) carry their label strings and are
 * validated against the vocabulary by the service. Fields typed [FieldChange]
 * distinguish "leave the stored value alone" from "write this value".
 */

/** Creates a project. A null [slug] means: derive it from [name]. */
public data class CreateProject(
    public val name: String,
    public val slug: String? = null,
    public val description: String? = null,
)

/**
 * Creates an item in a project. A null [slug] means: derive it from [name].
 * [parentRef] names the epic to sit under (leaves only); [releaseRef] names
 * the release to join (epics only).
 */
public data class CreateItem(
    public val type: String,
    public val name: String,
    public val slug: String? = null,
    public val description: String? = null,
    public val parentRef: String? = null,
    public val releaseRef: String? = null,
)

/**
 * Partially updates an item: every field defaults to keeping the stored
 * value. Setting [parentRef] to null moves the item to project level.
 */
public data class UpdateItem(
    public val name: FieldChange<String> = FieldChange.Keep,
    public val slug: FieldChange<String> = FieldChange.Keep,
    public val description: FieldChange<String?> = FieldChange.Keep,
    public val status: FieldChange<String> = FieldChange.Keep,
    public val type: FieldChange<String> = FieldChange.Keep,
    public val parentRef: FieldChange<String?> = FieldChange.Keep,
)

/**
 * Replaces an item's whole blocker set with [blockerRefs], deduplicated.
 * An empty list clears every blocker.
 */
public data class SetItemBlockedBy(
    public val blockerRefs: List<String>,
)

/** Creates a release in a project. A null [slug] means: derive it from [name]. */
public data class CreateRelease(
    public val name: String,
    public val slug: String? = null,
    public val description: String? = null,
    public val targetDate: LocalDate? = null,
)

/**
 * Partially updates a release: every field defaults to keeping the stored
 * value. Setting [targetDate] to null clears the date.
 */
public data class UpdateRelease(
    public val name: FieldChange<String> = FieldChange.Keep,
    public val slug: FieldChange<String> = FieldChange.Keep,
    public val description: FieldChange<String?> = FieldChange.Keep,
    public val status: FieldChange<String> = FieldChange.Keep,
    public val targetDate: FieldChange<LocalDate?> = FieldChange.Keep,
)

/**
 * Puts an epic into the release named by [releaseRef], or takes it out of
 * any release when [releaseRef] is null.
 */
public data class AssignEpicToRelease(
    public val releaseRef: String? = null,
)
