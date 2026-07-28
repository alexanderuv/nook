package io.nook.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The one text format both halves of the connection read and write, so that
 * neither can come to read a reply the other way.
 *
 * Two settings carry weight. Defaults are left out, so a field nobody supplied
 * is absent rather than present and empty. And an undefined field is refused
 * rather than ignored: both halves ship from one source tree, so a field no
 * operation defines is a defect to surface, and ignoring one would silently
 * drop something a caller meant.
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
 * Its conversion is written by hand because a field's state has to be decided
 * by whether the request mentions the field at all. A generated one has one
 * slot per field and no way to record that, which makes "leave the description
 * alone" and "clear the description" the same request in both directions.
 */
@Serializable(with = ItemUpdateSerializer::class)
public data class ItemUpdate(public val ref: String, public val changes: UpdateItem)

/** The payload of `update_release`, on the same terms as [ItemUpdate]. */
@Serializable(with = ReleaseUpdateSerializer::class)
public data class ReleaseUpdate(public val ref: String, public val changes: UpdateRelease)

public object ItemUpdateSerializer : KSerializer<ItemUpdate> {

    // Declaring the fields is what a conversion written by hand owes anyone who
    // asks the shape what it holds — and a field that may be set to nothing is
    // declared as one that accepts nothing, which is exactly the distinction
    // [clearableChange] makes below. So the declaration and the reading cannot
    // come to disagree.
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("io.nook.contract.ItemUpdate") {
        element<String>("ref", describedAs("The item to change, by its id or its handle."))
        element<String>(
            "name",
            describedAs("A new name for the item. Left out, the name is left alone."),
            isOptional = true,
        )
        element<String>(
            "slug",
            describedAs("A new handle for the item. Left out, the handle is left alone."),
            isOptional = true,
        )
        element<String?>(
            "description",
            describedAs(
                "A new description, or nothing at all to clear the one it has. " +
                    "Left out, the description is left alone.",
            ),
            isOptional = true,
        )
        element<String>(
            "status",
            describedAs(
                "A new status: todo, in_progress, done, or cancelled. Left out, the status is left alone.",
            ),
            isOptional = true,
        )
        element<String>(
            "type",
            describedAs("A new type: epic, task, bug, or chore. Left out, the type is left alone."),
            isOptional = true,
        )
        element<String?>(
            "parentRef",
            describedAs(
                "The epic to move the item under, by its id or its handle, or nothing at all to move it out to " +
                    "project level. Left out, where the item sits is left alone.",
            ),
            isOptional = true,
        )
        element<String?>(
            "releaseRef",
            describedAs(
                "The release to put the epic in, by its id or its handle, or nothing at all to take it out of " +
                    "every release. Left out, the release is left alone.",
            ),
            isOptional = true,
        )
        element<List<String>>(
            "blockedBy",
            describedAs(
                "The whole set of items this one waits on, each by its id or its handle. It replaces the set " +
                    "rather than adding to it, so an empty list clears it. Left out, the blockers are left alone.",
            ),
            isOptional = true,
        )
    }

    private val defined: Set<String> = descriptor.fieldNames().toSet()

    override fun serialize(encoder: Encoder, value: ItemUpdate) {
        val changes = value.changes
        encoder.jsonOutput().encodeJsonElement(
            buildJsonObject {
                put("ref", value.ref)
                putText("name", changes.name)
                putText("slug", changes.slug)
                putText("description", changes.description)
                putText("status", changes.status)
                putText("type", changes.type)
                putText("parentRef", changes.parentRef)
                putText("releaseRef", changes.releaseRef)
                putChange("blockedBy", changes.blockedBy) { refs ->
                    JsonArray(refs.map { JsonPrimitive(it) })
                }
            },
        )
    }

    override fun deserialize(decoder: Decoder): ItemUpdate {
        val payload = decoder.jsonInput().decodeJsonElement().asObject().also { it.refuseUndefined(defined) }
        return ItemUpdate(
            ref = payload.requiredText("ref"),
            changes = UpdateItem(
                name = payload.change("name") { it.asText("name") },
                slug = payload.change("slug") { it.asText("slug") },
                description = payload.clearableChange("description") { it.asText("description") },
                status = payload.change("status") { it.asText("status") },
                type = payload.change("type") { it.asText("type") },
                parentRef = payload.clearableChange("parentRef") { it.asText("parentRef") },
                releaseRef = payload.clearableChange("releaseRef") { it.asText("releaseRef") },
                blockedBy = payload.change("blockedBy") { element ->
                    element.asArray("blockedBy").map { it.asText("blockedBy") }
                },
            ),
        )
    }
}

public object ReleaseUpdateSerializer : KSerializer<ReleaseUpdate> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("io.nook.contract.ReleaseUpdate") {
        element<String>("ref", describedAs("The release to change, by its id or its handle."))
        element<String>(
            "name",
            describedAs("A new name for the release. Left out, the name is left alone."),
            isOptional = true,
        )
        element<String>(
            "slug",
            describedAs("A new handle for the release. Left out, the handle is left alone."),
            isOptional = true,
        )
        element<String?>(
            "description",
            describedAs(
                "A new description, or nothing at all to clear the one it has. " +
                    "Left out, the description is left alone.",
            ),
            isOptional = true,
        )
        element<String>(
            "status",
            describedAs(
                "A new status: planned, in_progress, released, or cancelled. Left out, the status is left alone.",
            ),
            isOptional = true,
        )
        element(
            "targetDate",
            LocalDateSerializer.descriptor.nullable,
            describedAs(
                "A new day to aim at, written as 2026-12-24, or nothing at all to clear the one it has. " +
                    "Left out, the day is left alone.",
            ),
            isOptional = true,
        )
    }

    private val defined: Set<String> = descriptor.fieldNames().toSet()

    override fun serialize(encoder: Encoder, value: ReleaseUpdate) {
        val output = encoder.jsonOutput()
        val changes = value.changes
        output.encodeJsonElement(
            buildJsonObject {
                put("ref", value.ref)
                putText("name", changes.name)
                putText("slug", changes.slug)
                putText("description", changes.description)
                putText("status", changes.status)
                putChange("targetDate", changes.targetDate) { date ->
                    output.json.encodeToJsonElement(LocalDateSerializer, date)
                }
            },
        )
    }

    override fun deserialize(decoder: Decoder): ReleaseUpdate {
        val input = decoder.jsonInput()
        val payload = input.decodeJsonElement().asObject().also { it.refuseUndefined(defined) }
        return ReleaseUpdate(
            ref = payload.requiredText("ref"),
            changes = UpdateRelease(
                name = payload.change("name") { it.asText("name") },
                slug = payload.change("slug") { it.asText("slug") },
                description = payload.clearableChange("description") { it.asText("description") },
                status = payload.change("status") { it.asText("status") },
                targetDate = payload.clearableChange("targetDate") { element ->
                    input.json.decodeFromJsonElement(LocalDateSerializer, element)
                },
            ),
        )
    }
}

// ── reading and writing a field by whether it is mentioned ───────────────────
//
// The three states of a partial-update field, in both directions: a field the
// request never names is left alone, a field named with a value is set to it,
// and a field named with nothing is cleared. Only fields the stored value may
// be taken away from accept the third — asking to clear a name is a mistake
// worth saying out loud rather than a state to carry.

/** Writes [change] under [name], or writes nothing at all when it is [FieldChange.Keep]. */
private fun <T : Any> JsonObjectBuilder.putChange(
    name: String,
    change: FieldChange<T?>,
    encode: (T) -> JsonElement,
) {
    if (change is FieldChange.Set) put(name, change.value?.let(encode) ?: JsonNull)
}

private fun JsonObjectBuilder.putText(name: String, change: FieldChange<String?>) {
    putChange(name, change) { JsonPrimitive(it) }
}

/** A field that carries a value whenever it is mentioned at all. */
private fun <T : Any> JsonObject.change(name: String, read: (JsonElement) -> T): FieldChange<T> =
    when (val element = this[name]) {
        null -> FieldChange.Keep
        JsonNull -> throw SerializationException("\"$name\" takes a value; it cannot be set to nothing")
        else -> FieldChange.Set(read(element))
    }

/** A field that may be set to nothing, which clears what is stored. */
private fun <T : Any> JsonObject.clearableChange(name: String, read: (JsonElement) -> T): FieldChange<T?> =
    when (val element = this[name]) {
        null -> FieldChange.Keep
        JsonNull -> FieldChange.Set(null)
        else -> FieldChange.Set(read(element))
    }

private fun JsonObject.requiredText(name: String): String =
    (this[name] ?: throw SerializationException("this operation requires \"$name\"")).asText(name)

private fun JsonObject.refuseUndefined(defined: Set<String>) {
    val undefined = keys - defined
    if (undefined.isNotEmpty()) {
        throw SerializationException(
            "this operation defines no field named ${undefined.sorted().joinToString { "\"$it\"" }}",
        )
    }
}

private fun JsonElement.asObject(): JsonObject =
    this as? JsonObject ?: throw SerializationException("a payload is a set of named fields, and $this is not")

private fun JsonElement.asText(name: String): String {
    val primitive = this as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
        throw SerializationException("\"$name\" takes text, and $this is not text")
    }
    return primitive.content
}

private fun JsonElement.asArray(name: String): List<JsonElement> =
    this as? JsonArray ?: throw SerializationException("\"$name\" takes a list, and $this is not one")

// The two halves of "this crosses as JSON alone". A conversion written by hand
// reaches past the format-neutral interface to the document itself, which is
// what makes deciding a field by its presence possible in the first place.

private fun Encoder.jsonOutput(): JsonEncoder =
    this as? JsonEncoder ?: throw SerializationException("this shape crosses as JSON alone")

private fun Decoder.jsonInput(): JsonDecoder =
    this as? JsonDecoder ?: throw SerializationException("this shape crosses as JSON alone")
