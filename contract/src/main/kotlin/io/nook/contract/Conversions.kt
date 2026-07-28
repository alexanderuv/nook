package io.nook.contract

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.enums.EnumEntries
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
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
 *
 * The accepted words are read off the members themselves and the name this
 * reports itself under is read off the vocabulary's own type, so neither can
 * drift from the vocabulary being crossed. That name is part of what a caller
 * sees while being no part of what a value encodes to, so a check beside these
 * states all four outright: a Kotlin rename would otherwise move a caller's
 * name with every round trip still passing.
 */
public sealed class LabelSerializer<T>(
    entries: EnumEntries<T>,
    type: Class<T>,
    private val singular: String,
    private val plural: String,
) : KSerializer<T> where T : Enum<T>, T : Labelled {

    private val byLabel: Map<String, T> = entries.associateBy { it.label }
    private val vocabulary: String = entries.joinToString { it.label }

    init {
        // Two members under one word leave the word meaning whichever of them
        // this map happened to keep, for every caller and every service at once.
        require(byLabel.size == entries.size) {
            "${type.canonicalName} has two members under one label: $vocabulary"
        }
    }

    // The canonical name rather than the plain one: a vocabulary nested inside
    // another type has a dollar sign in the name the runtime knows it by, where
    // every name a caller is shown here is dotted throughout.
    final override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(type.canonicalName, PrimitiveKind.STRING)

    /** The member [label] names, or nothing where the vocabulary has no such word. */
    internal fun of(label: String): T? = byLabel[label]

    /**
     * The member [label] names, or whatever [refuse] makes of the words this
     * vocabulary is refused in.
     *
     * A vocabulary is checked in more than one place — a label arrives across
     * the wire and is checked here, and the same label reaches a service and is
     * checked there — and the two fail in different ways: one is a payload that
     * could not be read, the other a verdict on a request that was. What they
     * must not differ in is what a caller is told, so the sentence is composed
     * once and each caller supplies only its own way of failing.
     */
    public fun of(label: String, refuse: (String) -> Nothing): T =
        byLabel[label] ?: refuse("\"$label\" is not $singular; the $plural are $vocabulary")

    final override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeString(value.label)
    }

    final override fun deserialize(decoder: Decoder): T =
        of(decoder.decodeString()) { throw SerializationException(it) }
}

public object ItemTypeSerializer : LabelSerializer<ItemType>(
    ItemType.entries, ItemType::class.java, "an item type", "item types",
)

public object ItemStatusSerializer : LabelSerializer<ItemStatus>(
    ItemStatus.entries, ItemStatus::class.java, "an item status", "item statuses",
)

public object ReleaseStatusSerializer : LabelSerializer<ReleaseStatus>(
    ReleaseStatus.entries, ReleaseStatus::class.java, "a release status", "release statuses",
)

public object ErrorCodeSerializer : LabelSerializer<ErrorCode>(
    ErrorCode.entries, ErrorCode::class.java, "a refusal code", "refusal codes",
)

/**
 * One value of the parent part: the epic's reference, or nothing at all for
 * "no epic above it". Nothing rather than a reserved word, because any word
 * this could reserve is a handle some project is entitled to give an epic.
 */
public object ParentFilterSerializer : KSerializer<ParentFilter> {
    // Declared as accepting nothing as well as text, because that is what it
    // reads and writes: "no epic above it" crosses as nothing at all. A
    // declaration saying text alone would be read by anyone deriving a shape
    // from it as forbidding the very value this vocabulary exists for.
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.nook.contract.ParentFilter", PrimitiveKind.STRING).nullable

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
