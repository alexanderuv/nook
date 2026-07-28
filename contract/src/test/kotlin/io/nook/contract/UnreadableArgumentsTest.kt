package io.nook.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a caller is told when an operation cannot read its arguments — for every
 * one of the eleven, and driven the way a request drives it.
 *
 * The three mistakes are the ones a caller actually makes: a field the
 * operation never defined, a required one left out, and a value of the wrong
 * kind. Each has to come back naming the field at fault, and none of them may
 * carry a word about how these shapes are turned into text: a caller cannot
 * change a setting in a library they do not have, cannot act on a class name
 * from inside this module, and did not ask to have their own request read back
 * to them.
 *
 * The cases are derived from each shape's own declaration rather than written
 * out, so an operation that gains a field is checked on it here without this
 * being edited.
 */
class UnreadableArgumentsTest {

    /**
     * A catalog that does nothing but say it was reached, which is the line
     * every request here is measured against: a request that could not be read
     * must stop short of it, and one that could must arrive.
     */
    private object Unreachable : OperationCatalog {
        private fun never(): Nothing = error(REACHED)
        override fun createProject(command: CreateProject) = never()
        override fun getProject(ref: String) = never()
        override fun listProjects() = never()
        override fun deleteProject(ref: String) = never()
        override fun createItem(projectRef: String, command: CreateItem) = never()
        override fun updateItem(projectRef: String, itemRef: String, command: UpdateItem) = never()
        override fun deleteItem(projectRef: String, itemRef: String) = never()
        override fun createRelease(projectRef: String, command: CreateRelease) = never()
        override fun updateRelease(projectRef: String, releaseRef: String, command: UpdateRelease) = never()
        override fun getItem(projectRef: String, itemRef: String) = never()
        override fun listItems(projectRef: String, filter: ItemFilter) = never()
    }

    /** Words that would mean a caller was handed something behind the surface instead of their own mistake. */
    private val leaks = listOf(
        "kotlinx",
        "serializ",
        "ignoreUnknownKeys",
        "isLenient",
        "coerceInputValues",
        "io.nook.",
        "JsonObject",
        "JsonPrimitive",
        "JsonElement",
    )

    /** What [operation] says when its arguments are [payload]: the message, having reached nothing. */
    private fun refusalOf(operation: CatalogOperation, payload: JsonObject): String {
        val wiring = wiringOf(operation)
        val inside = if (wiring is WiredOperation.ProjectScoped<*, *>) "search-revamp" else null
        val refused = assertFailsWith<SerializationException>("${operation.label} read arguments it should not have") {
            wiring.runAgainst(Unreachable, inside, payload)
        }
        val said = refused.message.orEmpty()
        assertTrue(said.isNotBlank(), "${operation.label} refused these arguments saying nothing at all")
        leaks.forEach {
            assertTrue(!said.contains(it, ignoreCase = true), "${operation.label} answered with $it: $said")
        }
        assertTrue(
            !said.contains(catalogJson.encodeToString(JsonObject.serializer(), payload)),
            "${operation.label} read the whole request back to its caller: $said",
        )
        return said
    }

    /** [payload] is refused by [operation] in words naming [field]. */
    private fun refusalNames(operation: CatalogOperation, field: String, payload: JsonObject) {
        val said = refusalOf(operation, payload)
        assertTrue(
            said.contains("\"$field\""),
            "${operation.label} did not name \"$field\" as the field at fault; it said: $said",
        )
    }

    @Test
    fun `a field the operation does not define is refused by name, for every operation`() {
        CatalogOperation.entries.forEach { operation ->
            val shape = operation.arguments()
            refusalNames(operation, "colour", JsonObject(shape.minimal() + ("colour" to JsonPrimitive("red"))))
        }
    }

    @Test
    fun `an argument the operation requires is refused by name when it is left out`() {
        val driven = CatalogOperation.entries.sumOf { operation ->
            val shape = operation.arguments()
            val minimal = shape.minimal()
            minimal.keys.onEach { required ->
                refusalNames(operation, required, JsonObject(minimal - required))
            }.size
        }
        // Two of the shapes require nothing at all: the one that asks for
        // nothing, and the filter, whose every part is optional by design. The
        // count is here so that a shape quietly losing a requirement shows up as
        // a failure rather than as one fewer case nobody drove.
        assertEquals(10, driven, "the required arguments of the eleven operations were not all driven")
    }

    @Test
    fun `an argument holding the wrong kind of value is refused by name`() {
        val driven = CatalogOperation.entries.sumOf { operation ->
            val shape = operation.arguments()
            val minimal = shape.minimal()
            shape.fieldNames().onEach { field ->
                refusalNames(operation, field, JsonObject(minimal + (field to WRONG_KIND)))
            }.size
        }
        assertEquals(37, driven, "the arguments of the eleven operations were not all driven")
    }

    @Test
    fun `a field that must always hold a value is refused in the words the hand-written conversions use`() {
        // Those two said this before anything was derived from a declaration,
        // and it is the right thing to say: what the caller asked for is to
        // take the stored value away, not to supply something of another kind.
        // So the derived check says it too, for every shape rather than for
        // two, and it is driven the way a request drives it.
        assertEquals(
            "\"name\" takes a value; it cannot be set to nothing",
            refusalOf(
                CatalogOperation.UPDATE_ITEM,
                catalogJson.parseToJsonElement("""{"ref":"add-search","name":null}""") as JsonObject,
            ),
        )
        assertEquals(
            "\"status\" takes a value; it cannot be set to nothing",
            refusalOf(
                CatalogOperation.UPDATE_RELEASE,
                catalogJson.parseToJsonElement("""{"ref":"autumn","status":null}""") as JsonObject,
            ),
        )
        // And the two conversions still say it on their own, where they are
        // read directly rather than through an operation.
        listOf<() -> Any>(
            { catalogJson.decodeFromString(ItemUpdateSerializer, """{"ref":"add-search","name":null}""") },
            { catalogJson.decodeFromString(ReleaseUpdateSerializer, """{"ref":"autumn","status":null}""") },
        ).forEach { byHand ->
            val said = assertFailsWith<SerializationException> { byHand() }.message.orEmpty()
            assertTrue(said.endsWith("takes a value; it cannot be set to nothing"), said)
        }
    }

    @Test
    fun `a field the stored value may be taken away from accepts nothing at all`() {
        // The other half of the same rule: a field declared as accepting
        // nothing is not refused for holding nothing, or "clear this" would be
        // unaskable. Reaching the catalog is what says the arguments were read.
        val reached = assertFailsWith<IllegalStateException> {
            wiringOf(CatalogOperation.UPDATE_ITEM).runAgainst(
                Unreachable,
                "search-revamp",
                catalogJson.parseToJsonElement("""{"ref":"add-search","description":null}""") as JsonObject,
            )
        }
        assertEquals(REACHED, reached.message)
    }

    @Test
    fun `a field of a list is refused against the list its caller was asked for`() {
        // The value at fault is inside the list rather than in place of it, and
        // what the caller has to be told is still what the field takes.
        assertEquals(
            "\"blockedBy\" takes a list of text, and 42 is not text",
            refusalOf(
                CatalogOperation.UPDATE_ITEM,
                catalogJson.parseToJsonElement("""{"ref":"add-search","blockedBy":["index-docs",42]}""") as JsonObject,
            ),
        )
    }

    private companion object {

        /** What the catalog says when a request got as far as it. */
        const val REACHED = "the catalog was reached"

        /** A value no field of any operation accepts: not text, not a list, not a set of named fields. */
        val WRONG_KIND: JsonElement = JsonPrimitive(42)

        fun CatalogOperation.arguments(): SerialDescriptor = wiringOf(this).payloadShape.descriptor

        /** The least this shape accepts: every field it requires, holding a value of the kind it asks for. */
        fun SerialDescriptor.minimal(): Map<String, JsonElement> =
            (0 until elementsCount)
                .filterNot(::isElementOptional)
                .associate { getElementName(it) to getElementDescriptor(it).someValue() }

        fun SerialDescriptor.someValue(): JsonElement = when (kind) {
            PrimitiveKind.BOOLEAN -> JsonPrimitive(true)
            StructureKind.LIST -> JsonArray(listOf(getElementDescriptor(0).someValue()))
            StructureKind.CLASS, StructureKind.OBJECT, StructureKind.MAP -> JsonObject(minimal())
            else -> JsonPrimitive("a value")
        }
    }
}
