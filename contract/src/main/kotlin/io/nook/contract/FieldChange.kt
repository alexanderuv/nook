package io.nook.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A partial-update field: [Keep] leaves the stored value alone, [Set] writes
 * the carried value — which may itself be null for nullable fields, where
 * "set to null" (clear) and "leave alone" mean different things.
 */
@Serializable(with = FieldChangeSerializer::class)
public sealed interface FieldChange<out T> {
    public data object Keep : FieldChange<Nothing>
    public data class Set<T>(public val value: T) : FieldChange<T>
}

/**
 * The three states of a partial-update field, in both directions, taught to the
 * library once instead of written out per field.
 *
 * Only a mention ever reaches here, which is what leaves room for the third
 * state. A field left alone is the property's declared default, and
 * [catalogJson] leaves defaults out, so [FieldChange.Keep] is not something a
 * shape can be asked to write; reading is the same boundary from the other
 * side, where a field the payload never names is never decoded and the default
 * stands. So "absent" is carried by the absence itself rather than by a value
 * that would have to be told apart from nothing.
 *
 * Which fields may be cleared is then the type argument's own nullability
 * rather than a second thing to declare: a [T] of `String?` carries a serializer
 * that accepts nothing at all, and one of `String` carries a serializer that
 * refuses it. That is the same distinction the declaration hands to whoever
 * reads the payload against it, so the two cannot come to disagree.
 */
public class FieldChangeSerializer<T>(private val inner: KSerializer<T>) : KSerializer<FieldChange<T>> {

    override val descriptor: SerialDescriptor = inner.descriptor

    override fun serialize(encoder: Encoder, value: FieldChange<T>) {
        if (value !is FieldChange.Set) {
            throw SerializationException("a field left alone is left out, so there is nothing to write")
        }
        inner.serialize(encoder, value.value)
    }

    override fun deserialize(decoder: Decoder): FieldChange<T> = FieldChange.Set(inner.deserialize(decoder))
}
