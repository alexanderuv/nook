@file:UseSerializers(LocalDateSerializer::class)

package io.nook.contract

import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * The write commands: one data class per mutation, carrying that mutation's
 * payload. References that *address* the target (which project, which item)
 * are not part of the payload — operations take them as separate parameters
 * next to the command.
 *
 * Enum-valued fields (item type, statuses) carry their label strings and are
 * validated against the vocabulary by the service. Fields typed [FieldChange]
 * distinguish "leave the stored value alone" from "write this value".
 *
 * The three create commands cross the wire as themselves. The two partial
 * updates do not: a generated conversion has one slot per field and no way to
 * record that a field was mentioned at all, which collapses "leave this alone"
 * and "clear this" into one request. Theirs is written by hand, alongside the
 * reference each addresses — see [ItemUpdate] and [ReleaseUpdate].
 */

/** Creates a project. A null [slug] means: derive it from [name]. */
@Serializable
public data class CreateProject(
    @Describes("What the project is called, in whatever words a person would use.")
    public val name: String,
    @Describes("The short lowercase handle the project is addressed by. Left out, it is derived from the name.")
    public val slug: String? = null,
    @Describes("What the project is for. Left out, it has none.")
    public val description: String? = null,
)

/**
 * Creates an item in a project. A null [slug] means: derive it from [name].
 * [parentRef] names the epic to sit under (leaves only); [releaseRef] names
 * the release to join (epics only).
 */
@Serializable
public data class CreateItem(
    @Describes("What kind of item to make: epic, task, bug, or chore. An epic holds other items; the rest are leaves.")
    public val type: String,
    @Describes("What the item is called, in whatever words a person would use.")
    public val name: String,
    @Describes("The short lowercase handle the item is addressed by. Left out, it is derived from the name.")
    public val slug: String? = null,
    @Describes("What the item is about, at whatever length is useful. Left out, it has none.")
    public val description: String? = null,
    @Describes("The epic this item sits under, by its id or its handle. Leaves only; left out, it sits on the project.")
    public val parentRef: String? = null,
    @Describes("The release this item joins, by its id or its handle. Epics only; left out, it joins none.")
    public val releaseRef: String? = null,
)

/**
 * Partially updates an item: every field defaults to keeping the stored
 * value. This is the one way an item changes, whatever the field.
 *
 * Setting [parentRef] to null moves the item to project level; setting
 * [releaseRef] to null takes an epic out of every release. [blockedBy]
 * replaces the whole blocker set rather than adding to it — an empty list
 * clears it — and duplicates within it collapse to one edge.
 *
 * The last two are gated on what the item *will be* once this command lands,
 * not on what it is now: [releaseRef] applies to an epic and [blockedBy] to a
 * leaf, so a command supplying either alongside a [type] that contradicts it is
 * refused. That keeps each field's rule readable off this one command, and it
 * is why the guards on a type change need not reason about them.
 */
public data class UpdateItem(
    public val name: FieldChange<String> = FieldChange.Keep,
    public val slug: FieldChange<String> = FieldChange.Keep,
    public val description: FieldChange<String?> = FieldChange.Keep,
    public val status: FieldChange<String> = FieldChange.Keep,
    public val type: FieldChange<String> = FieldChange.Keep,
    public val parentRef: FieldChange<String?> = FieldChange.Keep,
    public val releaseRef: FieldChange<String?> = FieldChange.Keep,
    public val blockedBy: FieldChange<List<String>> = FieldChange.Keep,
)

/** Creates a release in a project. A null [slug] means: derive it from [name]. */
@Serializable
public data class CreateRelease(
    @Describes("What the release is called, in whatever words a person would use.")
    public val name: String,
    @Describes("The short lowercase handle the release is addressed by. Left out, it is derived from the name.")
    public val slug: String? = null,
    @Describes("What the release is for. Left out, it has none.")
    public val description: String? = null,
    @Describes("The day the release is aimed at, written as 2026-12-24. Left out, it has no date.")
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
