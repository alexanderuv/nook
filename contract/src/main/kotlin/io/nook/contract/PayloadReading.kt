package io.nook.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a caller is told about arguments an operation cannot read, said in
 * Nook's own words.
 *
 * Left to the serialization library, three of these come back quoting it: two
 * tell the caller to change a setting in a library the caller does not have,
 * one echoes the whole request back, and one names a class nobody outside this
 * module has heard of. A reply describes the caller's call and what they can do
 * about it, and nothing behind the surface — so the payload is read against the
 * shape's own declaration first, and the library never gets to speak.
 *
 * The declaration is enough to say all three things, because it already holds
 * all three: which fields a shape defines, which of them may be left out, and
 * what kind of value each takes. So an operation that gains a field is checked
 * on it without anything here being edited, and the two conversions written by
 * hand — which have always said this in these words — are the model the rest
 * are held to.
 */
internal fun SerialDescriptor.refuseUnreadable(payload: JsonObject) {
    namedFields(prefix = "", shape = this, payload = payload)
}

/**
 * The two mistakes about which fields are there — one the shape does not
 * define, one it requires and the payload leaves out — and then each field it
 * does carry, against what the shape says that field takes.
 *
 * [prefix] is what a field of a nested shape is called from outside it, so that
 * the name a refusal gives is the one the caller wrote.
 */
private fun namedFields(prefix: String, shape: SerialDescriptor, payload: JsonObject) {
    val defined = shape.fieldNames()
    val undefined = payload.keys - defined.toSet()
    if (undefined.isNotEmpty()) {
        throw SerializationException(
            "this operation defines no field named ${undefined.sorted().joinToString { "\"$prefix$it\"" }}",
        )
    }
    defined.forEachIndexed { index, name ->
        val supplied = payload[name]
        val declared = shape.getElementDescriptor(index)
        when {
            supplied == null ->
                if (!shape.isElementOptional(index)) {
                    throw SerializationException("this operation requires \"$prefix$name\"")
                }

            // A field named with nothing in it is its own mistake rather than a
            // value of the wrong kind: what the caller asked for is to take the
            // stored value away, and a field that must always hold one says so
            // in those terms. Only a field the value may be taken away from is
            // declared as accepting nothing.
            supplied is JsonNull && !declared.isNullable ->
                throw SerializationException("\"$prefix$name\" takes a value; it cannot be set to nothing")

            else -> value("$prefix$name", declared.takes(), declared, supplied)
        }
    }
}

/**
 * One value against what its field was declared to take.
 *
 * [takes] is the whole field's declaration and stays the same all the way down,
 * while [shape] narrows to whatever is being looked at — so a list of text
 * whose third value is a number is reported against the list the caller was
 * asked for rather than against the value's own kind. Nothing inside a list is
 * a field the caller can be told to leave alone, which is why nothing here is
 * about setting a field to nothing.
 */
private fun value(field: String, takes: String, shape: SerialDescriptor, element: JsonElement) {
    if (element is JsonNull) {
        if (!shape.isNullable) throw mismatch(field, takes, shape, element)
        return
    }
    when (shape.kind) {
        StructureKind.LIST -> {
            val held = element as? JsonArray ?: throw mismatch(field, takes, shape, element)
            held.forEach { value(field, takes, shape.getElementDescriptor(0), it) }
        }

        StructureKind.MAP -> {
            val held = element as? JsonObject ?: throw mismatch(field, takes, shape, element)
            held.values.forEach { value(field, takes, shape.getElementDescriptor(1), it) }
        }

        StructureKind.CLASS, StructureKind.OBJECT ->
            namedFields("$field.", shape, element as? JsonObject ?: throw mismatch(field, takes, shape, element))

        PrimitiveKind.BOOLEAN ->
            if (element.asNumberOrWord()?.toBooleanStrictOrNull() == null) throw mismatch(field, takes, shape, element)

        in numbers ->
            if (element.asNumberOrWord()?.toDoubleOrNull() == null) throw mismatch(field, takes, shape, element)

        // Text, and the vocabularies that cross as text. Nothing else reaches
        // here: no operation's arguments hold a value whose kind is decided by
        // the value itself.
        else -> if ((element as? JsonPrimitive)?.isString != true) throw mismatch(field, takes, shape, element)
    }
}

/** The kinds a number crosses as, which no operation's arguments hold today and any later one might. */
private val numbers: Set<SerialKind> = setOf(
    PrimitiveKind.BYTE,
    PrimitiveKind.SHORT,
    PrimitiveKind.INT,
    PrimitiveKind.LONG,
    PrimitiveKind.FLOAT,
    PrimitiveKind.DOUBLE,
)

/** What a value that is not written as text says, or nothing at all where it is. */
private fun JsonElement.asNumberOrWord(): String? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content

private fun mismatch(field: String, takes: String, shape: SerialDescriptor, element: JsonElement) =
    SerializationException("\"$field\" takes $takes, and $element ${shape.notThat()}")

/** What this shape accepts, in the words a caller reads. */
private fun SerialDescriptor.takes(): String = when (kind) {
    PrimitiveKind.BOOLEAN -> "true or false"
    in numbers -> "a number"
    StructureKind.LIST -> "a list of ${getElementDescriptor(0).takes()}"
    StructureKind.CLASS, StructureKind.OBJECT, StructureKind.MAP -> "a set of named fields"
    else -> "text"
}

/** The other half of the same sentence: what the value it was given is not. */
private fun SerialDescriptor.notThat(): String = when (kind) {
    PrimitiveKind.BOOLEAN -> "is neither"
    in numbers, StructureKind.LIST, StructureKind.CLASS, StructureKind.OBJECT, StructureKind.MAP -> "is not one"
    else -> "is not text"
}
