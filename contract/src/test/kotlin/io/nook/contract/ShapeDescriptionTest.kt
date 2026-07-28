package io.nook.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

/**
 * What the seven project-scoped operations say about themselves, held here
 * rather than wherever they are offered.
 *
 * An adapter that offers these by name has to tell a caller what each one does,
 * what arguments it takes, which of them may be left out, and what each argument
 * means. Every one of those is a fact about the shape, so an adapter that had to
 * supply any of them would be keeping a second copy of the contract in step by
 * discipline. These checks are what make that unnecessary: a field with no words
 * on it, or a conversion that reads a field it never declared, fails here — at
 * the source — rather than turning up as a gap in whatever is serving them.
 */
class ShapeDescriptionTest {

    private val vocabularies = mapOf(
        "item types" to ItemType.entries.map { it.label },
        "item statuses" to ItemStatus.entries.map { it.label },
        "release statuses" to ReleaseStatus.entries.map { it.label },
    )

    /** Every field of every payload the seven take, as `operation.field`, with what it says about itself. */
    private fun everyField(): List<Pair<String, String?>> = projectOperations.flatMap { operation ->
        operation.arguments.fieldNames().mapIndexed { index, field ->
            "${operation.name}.$field" to operation.arguments.describes(index)
        }
    }

    @Test
    fun `the seven that act inside a project are the seven the design names`() {
        assertEquals(
            listOf(
                "create_item", "update_item", "delete_item",
                "create_release", "update_release", "get_item", "list_items",
            ).sorted(),
            projectOperations.map { it.name }.sorted(),
        )
    }

    @Test
    fun `every one of the seven says what it does`() {
        projectOperations.forEach { operation ->
            assertTrue(operation.description.isNotBlank(), "${operation.name} says nothing about what it does")
        }
    }

    @Test
    fun `every payload of the seven names its fields`() {
        projectOperations.forEach { operation ->
            assertTrue(
                operation.arguments.fieldNames().isNotEmpty(),
                "${operation.name} takes a payload that names no field, so nothing can be built from it",
            )
        }
    }

    @Test
    fun `every field of every one of the seven says what it is for`() {
        everyField().forEach { (field, description) ->
            assertTrue(
                description != null && description.isNotBlank(),
                "$field carries no description, so a caller has only its name to go on",
            )
        }
    }

    /**
     * The vocabularies travel as text, so nothing turns a mistyped status back
     * before the core sees it — which is deliberate, and leaves the words a
     * caller may write to be spelled out in prose. This is what keeps that prose
     * from going stale: a fifth item type, and no description names it.
     */
    @Test
    fun `each vocabulary is spelled out in full somewhere a caller reads`() {
        val descriptions = everyField().mapNotNull { (_, description) -> description }
        vocabularies.forEach { (vocabulary, labels) ->
            assertTrue(
                descriptions.any { words -> labels.all { words.contains(it) } },
                "no field spells out the $vocabulary ($labels), so a caller cannot learn them without being refused",
            )
        }
    }

    /**
     * The two partial updates are the shapes where a declared field and a read
     * field could most easily part company, because what each field carries is a
     * state rather than a value. A field declared and not accepted is a lie to
     * whoever built a caller from the declaration; a field accepted and not
     * declared is invisible to them.
     */
    @Test
    fun `each partial update accepts exactly the fields it declares`() {
        listOf<Pair<String, (JsonObject) -> Unit>>(
            "update_item" to { payload -> catalogJson.decodeFromJsonElement(ItemUpdate.serializer(), payload) },
            "update_release" to { payload -> catalogJson.decodeFromJsonElement(ReleaseUpdate.serializer(), payload) },
        ).forEach { (operation, read) ->
            val declared = projectOperations.single { it.name == operation }.arguments.fieldNames()

            // Accepting what it declares: every field at once, each carrying a
            // value of the kind it declared, read without complaint.
            read(payloadNaming(declared))

            // And accepting nothing else: one undefined field is refused, and
            // the refusal names it.
            val refused = runCatching { read(payloadNaming(declared + "colour")) }.exceptionOrNull()
            assertTrue(
                refused?.message?.contains("colour") == true,
                "$operation accepted a field it never declared; it said: ${refused?.message}",
            )
        }
    }

    /** A payload naming every one of [fields], each with a value its declaration accepts. */
    private fun payloadNaming(fields: List<String>): JsonObject = JsonObject(
        fields.associateWith { field ->
            when (field) {
                "blockedBy" -> catalogJson.parseToJsonElement("""["index-docs"]""")
                "targetDate" -> catalogJson.parseToJsonElement(""""2026-12-24"""")
                else -> catalogJson.parseToJsonElement(""""some-text"""")
            }
        },
    )
}
