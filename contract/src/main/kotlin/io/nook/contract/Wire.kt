@file:UseSerializers(LocalDateSerializer::class)

package io.nook.contract

import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json

/**
 * The one text format both halves of the connection read and write, so that
 * neither can come to read a reply the other way.
 *
 * Two settings carry weight. Defaults are left out, which is more than tidiness:
 * it is what lets a partial update say "leave this field alone", because the
 * field a caller never mentions is the one this never writes — see
 * [FieldChangeSerializer]. And an undefined field is refused rather than
 * ignored: both halves ship from one source tree, so a field no operation
 * defines is a defect to surface, and ignoring one would silently drop
 * something a caller meant.
 *
 * What a call travels in is not defined here at all — see [RpcRequest], where
 * the shape is the standard's rather than Nook's.
 */
public val catalogJson: Json = Json {
    encodeDefaults = false
}

/** The payload of `list_projects`, which asks for nothing: an empty object. */
@Serializable
public data object EmptyPayload

/**
 * The payload of every operation whose whole request is the entity it names:
 * `get_project`, `delete_project`, `get_item`, `delete_item`. Which kind of
 * entity [ref] names is settled by the operation, and the project it sits in,
 * where there is one, is named by the request rather than by this.
 */
@Serializable
public data class TargetRef(
    @Describes("What this call is about, by its id or its handle.")
    public val ref: String,
)

/**
 * The payload of `update_item`: the item, and the fields to change, flat
 * together — `{"ref": "add-search", "status": "done"}`.
 *
 * The fields are held flat rather than as the [UpdateItem] the core takes,
 * because that is the shape on the wire, and a shape that declares what it
 * carries is one nothing has to be written by hand to read. [changes] and the
 * constructor beside it are the two ends of the same translation, and each
 * names every field once, so a field added to one that is missed in the other
 * does not compile.
 *
 * What makes a generated conversion enough here is [FieldChange] knowing its
 * own three states — a field left out, a field set, and a field cleared.
 */
@Serializable
public data class ItemUpdate(
    @Describes("The item to change, by its id or its handle.")
    public val ref: String,
    @Describes("A new name for the item. Left out, the name is left alone.")
    public val name: FieldChange<String> = FieldChange.Keep,
    @Describes("A new handle for the item. Left out, the handle is left alone.")
    public val slug: FieldChange<String> = FieldChange.Keep,
    @Describes(
        "A new description, or nothing at all to clear the one it has. " +
            "Left out, the description is left alone.",
    )
    public val description: FieldChange<String?> = FieldChange.Keep,
    @Describes("A new status: todo, in_progress, done, or cancelled. Left out, the status is left alone.")
    public val status: FieldChange<String> = FieldChange.Keep,
    @Describes("A new type: epic, task, bug, or chore. Left out, the type is left alone.")
    public val type: FieldChange<String> = FieldChange.Keep,
    @Describes(
        "The epic to move the item under, by its id or its handle, or nothing at all to move it out to " +
            "project level. Left out, where the item sits is left alone.",
    )
    public val parentRef: FieldChange<String?> = FieldChange.Keep,
    @Describes(
        "The release to put the epic in, by its id or its handle, or nothing at all to take it out of " +
            "every release. Left out, the release is left alone.",
    )
    public val releaseRef: FieldChange<String?> = FieldChange.Keep,
    @Describes(
        "The whole set of items this one waits on, each by its id or its handle. It replaces the set " +
            "rather than adding to it, so an empty list clears it. Left out, the blockers are left alone.",
    )
    public val blockedBy: FieldChange<List<String>> = FieldChange.Keep,
) {

    public constructor(ref: String, changes: UpdateItem) : this(
        ref = ref,
        name = changes.name,
        slug = changes.slug,
        description = changes.description,
        status = changes.status,
        type = changes.type,
        parentRef = changes.parentRef,
        releaseRef = changes.releaseRef,
        blockedBy = changes.blockedBy,
    )

    /** What the request asks to change, in the terms the core is called in. */
    public val changes: UpdateItem
        get() = UpdateItem(name, slug, description, status, type, parentRef, releaseRef, blockedBy)
}

/** The payload of `update_release`, on the same terms as [ItemUpdate]. */
@Serializable
public data class ReleaseUpdate(
    @Describes("The release to change, by its id or its handle.")
    public val ref: String,
    @Describes("A new name for the release. Left out, the name is left alone.")
    public val name: FieldChange<String> = FieldChange.Keep,
    @Describes("A new handle for the release. Left out, the handle is left alone.")
    public val slug: FieldChange<String> = FieldChange.Keep,
    @Describes(
        "A new description, or nothing at all to clear the one it has. " +
            "Left out, the description is left alone.",
    )
    public val description: FieldChange<String?> = FieldChange.Keep,
    @Describes(
        "A new status: planned, in_progress, released, or cancelled. Left out, the status is left alone.",
    )
    public val status: FieldChange<String> = FieldChange.Keep,
    @Describes(
        "A new day to aim at, written as 2026-12-24, or nothing at all to clear the one it has. " +
            "Left out, the day is left alone.",
    )
    public val targetDate: FieldChange<LocalDate?> = FieldChange.Keep,
) {

    public constructor(ref: String, changes: UpdateRelease) : this(
        ref = ref,
        name = changes.name,
        slug = changes.slug,
        description = changes.description,
        status = changes.status,
        targetDate = changes.targetDate,
    )

    /** What the request asks to change, in the terms the core is called in. */
    public val changes: UpdateRelease get() = UpdateRelease(name, slug, description, status, targetDate)
}
