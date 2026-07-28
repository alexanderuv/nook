package io.nook.contract

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.descriptors.SerialDescriptor

/**
 * What a field is for, in the words a caller should read.
 *
 * A documentation comment is gone by the time the program runs, so an adapter
 * that has to tell its callers what a field means would otherwise write those
 * words a second time, beside the shape instead of on it — and the copy is
 * wrong the first time a field changes. This keeps them on the field, where the
 * field's name and whether it may be left out already are.
 *
 * The target is the property rather than the constructor parameter deliberately:
 * left unsaid, the compiler binds an annotation on a constructor parameter to
 * the parameter, and nothing there survives into the shape's own description of
 * itself.
 */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class Describes(public val text: String)

/** What [Describes] says about the field at [index], or nothing where it carries none. */
public fun SerialDescriptor.describes(index: Int): String? =
    getElementAnnotations(index).filterIsInstance<Describes>().firstOrNull()?.text

/** The names of this shape's fields, in the order it declares them. */
public fun SerialDescriptor.fieldNames(): List<String> =
    (0 until elementsCount).map(::getElementName)
