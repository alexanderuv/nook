package io.nook.mcp

import io.nook.contract.describes
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind

/**
 * A payload shape's own description of itself, as the protocol's description of
 * a tool's arguments.
 *
 * Everything it says comes from the shape: the field names, which of them may
 * be left out, whether each takes text or a list or nothing at all, and the
 * words on each. Nothing is written down here, which is the point — a command
 * that gains a field gains an argument, and there is no second list to forget.
 *
 * Two things are deliberately absent. There is no closed list of allowed values
 * for the vocabularies: they are text as far as the shapes are concerned, the
 * core validates them and spells out the words a caller could have written, and
 * a list here would have the protocol library turn a mistyped status back in its
 * own wording before the core ever saw it — the same call reaching two different
 * verdicts depending on which adapter it came in by. And there is no default for
 * anything: a field the caller left out arrives left out.
 *
 * What is present is the marker refusing any argument the shape does not
 * define, which is what has the library turn back an undefined argument before
 * anything reaches the store.
 */
internal fun SerialDescriptor.asArguments(): Map<String, Any> = objectSchema(this)

private fun objectSchema(shape: SerialDescriptor): Map<String, Any> {
    val fields = (0 until shape.elementsCount)
    return buildMap {
        put("type", "object")
        put(
            "properties",
            fields.associate { field ->
                shape.getElementName(field) to fieldSchema(shape, field)
            },
        )
        val required = fields.filterNot(shape::isElementOptional).map(shape::getElementName)
        if (required.isNotEmpty()) put("required", required)
        put("additionalProperties", false)
    }
}

private fun fieldSchema(shape: SerialDescriptor, field: Int): Map<String, Any> = buildMap {
    putAll(valueSchema(shape.getElementDescriptor(field), "${shape.serialName}.${shape.getElementName(field)}"))
    shape.describes(field)?.let { put("description", it) }
}

/**
 * One value's schema. A value that may be nothing says so as a pair of kinds
 * rather than as a separate case, because that is the whole of the distinction
 * a partial update rests on: a field accepting nothing can be cleared, and one
 * that does not, cannot.
 *
 * [field] names where this value sits, for the one message this can produce: a
 * shape holding something no description can be derived from stops the server
 * as it is built, rather than being served as a tool nobody can call correctly.
 */
private fun valueSchema(value: SerialDescriptor, field: String): Map<String, Any> {
    val schema = buildMap<String, Any> {
        when (val kind = value.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> put("type", "string")
            PrimitiveKind.BOOLEAN -> put("type", "boolean")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> put("type", "integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> put("type", "number")
            StructureKind.LIST -> {
                put("type", "array")
                put("items", valueSchema(value.getElementDescriptor(0), "$field's values"))
            }
            StructureKind.CLASS, StructureKind.OBJECT -> putAll(objectSchema(value))
            else -> error(
                "no argument can be described for $field: it is $kind, and this describes text, " +
                    "true and false, numbers, lists and sets of named fields",
            )
        }
    }
    return if (value.isNullable) schema + ("type" to listOf(schema.getValue("type"), "null")) else schema
}
