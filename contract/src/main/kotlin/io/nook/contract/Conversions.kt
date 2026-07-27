package io.nook.contract

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

// The conversions nothing writes for us, for two different reasons.
//
// A moment and a calendar date are types the serialization library has never
// heard of, so their text form has to be stated. The vocabularies it would
// convert on its own, as the names their members carry in Kotlin — and those
// are the wrong words: a caller writes `task` and `in_progress`, which is the
// label every member already carries, so the label is what crosses.
//
// A conversion written by hand that is missing a case compiles and simply says
// less than it used to, with no complaint anywhere. That is why the checks
// beside these compare whole values rather than named fields.

/**
 * A moment, as the text it prints itself as — `2026-07-27T02:27:11.357547Z`.
 * The store keeps microseconds and this carries every digit of them; a moment
 * read back names the same instant, which is the whole of what it promises.
 */
public object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return try {
            Instant.parse(text)
        } catch (malformed: DateTimeParseException) {
            throw SerializationException("\"$text\" is not a moment in time", malformed)
        }
    }
}

/** A calendar date, as `2026-12-24`: a day, carrying no time and no zone. */
public object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): LocalDate {
        val text = decoder.decodeString()
        return try {
            LocalDate.parse(text)
        } catch (malformed: DateTimeParseException) {
            throw SerializationException("\"$text\" is not a calendar date", malformed)
        }
    }
}

/**
 * A vocabulary crossing as the labels its members already carry, rather than as
 * the names they happen to have in Kotlin. A member renamed in Kotlin therefore
 * changes nothing a caller sees, and the words a caller writes are the words the
 * services already validate against.
 *
 * A label outside the vocabulary is refused with the vocabulary spelled out, so
 * a caller who mistypes a status learns the four it could have written.
 */
public abstract class LabelSerializer<T> internal constructor(
    serialName: String,
    private val singular: String,
    private val plural: String,
    private val labels: List<String>,
) : KSerializer<T> {

    final override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    protected abstract fun labelOf(value: T): String

    protected abstract fun ofLabel(label: String): T?

    final override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeString(labelOf(value))
    }

    final override fun deserialize(decoder: Decoder): T {
        val label = decoder.decodeString()
        return ofLabel(label)
            ?: throw SerializationException(
                "\"$label\" is not $singular; the $plural are ${labels.joinToString()}",
            )
    }
}

public object ItemTypeSerializer : LabelSerializer<ItemType>(
    "io.nook.contract.ItemType",
    "an item type",
    "item types",
    ItemType.entries.map { it.label },
) {
    override fun labelOf(value: ItemType): String = value.label
    override fun ofLabel(label: String): ItemType? = ItemType.fromLabel(label)
}

public object ItemStatusSerializer : LabelSerializer<ItemStatus>(
    "io.nook.contract.ItemStatus",
    "an item status",
    "item statuses",
    ItemStatus.entries.map { it.label },
) {
    override fun labelOf(value: ItemStatus): String = value.label
    override fun ofLabel(label: String): ItemStatus? = ItemStatus.fromLabel(label)
}

public object ReleaseStatusSerializer : LabelSerializer<ReleaseStatus>(
    "io.nook.contract.ReleaseStatus",
    "a release status",
    "release statuses",
    ReleaseStatus.entries.map { it.label },
) {
    override fun labelOf(value: ReleaseStatus): String = value.label
    override fun ofLabel(label: String): ReleaseStatus? = ReleaseStatus.fromLabel(label)
}

public object ErrorCodeSerializer : LabelSerializer<ErrorCode>(
    "io.nook.contract.ErrorCode",
    "a refusal code",
    "refusal codes",
    ErrorCode.entries.map { it.label },
) {
    override fun labelOf(value: ErrorCode): String = value.label
    override fun ofLabel(label: String): ErrorCode? = ErrorCode.entries.firstOrNull { it.label == label }
}

/**
 * One value of the parent part: the epic's reference, or nothing at all for
 * "no epic above it". Nothing rather than a reserved word, because any word
 * this could reserve is a handle some project is entitled to give an epic.
 */
public object ParentFilterSerializer : KSerializer<ParentFilter> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.nook.contract.ParentFilter", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ParentFilter) {
        val json = encoder as? JsonEncoder
            ?: throw SerializationException("a parent filter crosses as JSON alone")
        json.encodeJsonElement(
            when (value) {
                ParentFilter.NoEpic -> JsonNull
                is ParentFilter.Epic -> JsonPrimitive(value.ref)
            },
        )
    }

    override fun deserialize(decoder: Decoder): ParentFilter {
        val json = decoder as? JsonDecoder
            ?: throw SerializationException("a parent filter crosses as JSON alone")
        val element = json.decodeJsonElement()
        return when {
            element == JsonNull -> ParentFilter.NoEpic
            element is JsonPrimitive && element.isString -> ParentFilter.Epic(element.content)
            else -> throw SerializationException(
                "a parent value is an epic's reference or nothing at all, and $element is neither",
            )
        }
    }
}
