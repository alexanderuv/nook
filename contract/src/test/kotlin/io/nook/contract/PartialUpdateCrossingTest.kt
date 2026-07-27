package io.nook.contract

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.jsonObject

/**
 * The three states of a partial-update field, across the wire and back: a field
 * the request never names, a field named with a value, and a field named with
 * nothing. Every state of every field is driven, because the two that collapse
 * into each other when this is got wrong — "leave the description alone" and
 * "clear the description" — look identical from anywhere except here.
 *
 * The last check in each half is the guard against the failure nothing else
 * catches: a conversion written by hand that never learned about a field
 * compiles perfectly and drops that field in silence.
 */
class PartialUpdateCrossingTest {

    private fun crossed(update: ItemUpdate): ItemUpdate =
        catalogJson.decodeFromString(
            ItemUpdateSerializer,
            catalogJson.encodeToString(ItemUpdateSerializer, update),
        )

    private fun crossed(update: ReleaseUpdate): ReleaseUpdate =
        catalogJson.decodeFromString(
            ReleaseUpdateSerializer,
            catalogJson.encodeToString(ReleaseUpdateSerializer, update),
        )

    private fun itemUpdate(changes: UpdateItem) = ItemUpdate("add-search", changes)

    private fun releaseUpdate(changes: UpdateRelease) = ReleaseUpdate("v1", changes)

    private val everyItemField = UpdateItem(
        name = FieldChange.Set("Renamed"),
        slug = FieldChange.Set("renamed"),
        description = FieldChange.Set("Two lines,\nand \"quotation marks\""),
        status = FieldChange.Set("in_progress"),
        type = FieldChange.Set("bug"),
        parentRef = FieldChange.Set("search-core"),
        releaseRef = FieldChange.Set("v1"),
        blockedBy = FieldChange.Set(listOf("index-docs", "index-docs", "rank-results")),
    )

    private val everyReleaseField = UpdateRelease(
        name = FieldChange.Set("Renamed"),
        slug = FieldChange.Set("renamed"),
        description = FieldChange.Set("Shipping in the autumn"),
        status = FieldChange.Set("in_progress"),
        targetDate = FieldChange.Set(LocalDate.of(2026, 12, 24)),
    )

    @Test
    fun `every state of every item field comes back as it went out`() {
        listOf(
            UpdateItem(),
            UpdateItem(name = FieldChange.Set("Renamed")),
            UpdateItem(description = FieldChange.Set(null)),
            UpdateItem(description = FieldChange.Set("A description")),
            UpdateItem(description = FieldChange.Set("")),
            UpdateItem(parentRef = FieldChange.Set(null)),
            UpdateItem(releaseRef = FieldChange.Set(null)),
            UpdateItem(releaseRef = FieldChange.Set("v1")),
            UpdateItem(blockedBy = FieldChange.Set(emptyList())),
            UpdateItem(blockedBy = FieldChange.Set(listOf("index-docs", "index-docs"))),
            UpdateItem(status = FieldChange.Set("done")),
            UpdateItem(type = FieldChange.Set("chore")),
            UpdateItem(slug = FieldChange.Set("renamed")),
            everyItemField,
        ).forEach { changes ->
            assertEquals(itemUpdate(changes), crossed(itemUpdate(changes)))
        }
    }

    @Test
    fun `every state of every release field comes back as it went out`() {
        listOf(
            UpdateRelease(),
            UpdateRelease(name = FieldChange.Set("Renamed")),
            UpdateRelease(description = FieldChange.Set(null)),
            UpdateRelease(description = FieldChange.Set("Shipping in the autumn")),
            UpdateRelease(status = FieldChange.Set("released")),
            UpdateRelease(slug = FieldChange.Set("renamed")),
            UpdateRelease(targetDate = FieldChange.Set(null)),
            UpdateRelease(targetDate = FieldChange.Set(LocalDate.of(2026, 12, 24))),
            everyReleaseField,
        ).forEach { changes ->
            assertEquals(releaseUpdate(changes), crossed(releaseUpdate(changes)))
        }
    }

    @Test
    fun `leaving a field alone and setting it to nothing are never the same request`() {
        // Both directions, because the collapse this guards against happens in
        // both: two different commands that write the same text, and one text
        // that reads back as the wrong one of the two.
        listOf(
            UpdateItem() to UpdateItem(description = FieldChange.Set(null)),
            UpdateItem() to UpdateItem(parentRef = FieldChange.Set(null)),
            UpdateItem() to UpdateItem(releaseRef = FieldChange.Set(null)),
            UpdateItem() to UpdateItem(blockedBy = FieldChange.Set(emptyList())),
        ).forEach { (untouched, cleared) ->
            assertNotEquals(
                catalogJson.encodeToString(ItemUpdateSerializer, itemUpdate(untouched)),
                catalogJson.encodeToString(ItemUpdateSerializer, itemUpdate(cleared)),
            )
            assertNotEquals(crossed(itemUpdate(untouched)), crossed(itemUpdate(cleared)))
        }

        assertNotEquals(
            catalogJson.encodeToString(ReleaseUpdateSerializer, releaseUpdate(UpdateRelease())),
            catalogJson.encodeToString(
                ReleaseUpdateSerializer,
                releaseUpdate(UpdateRelease(targetDate = FieldChange.Set(null))),
            ),
        )
    }

    @Test
    fun `an update naming no field writes nothing but the item it names`() {
        assertEquals(
            """{"ref":"add-search"}""",
            catalogJson.encodeToString(ItemUpdateSerializer, itemUpdate(UpdateItem())),
        )
    }

    @Test
    fun `both conversions carry every field their command declares`() {
        listOf(
            UpdateItem::class.java to
                catalogJson.encodeToJsonElement(ItemUpdateSerializer, itemUpdate(everyItemField)),
            UpdateRelease::class.java to
                catalogJson.encodeToJsonElement(ReleaseUpdateSerializer, releaseUpdate(everyReleaseField)),
        ).forEach { (command, written) ->
            assertEquals(
                command.declaredFields.map { it.name }.toSet() + "ref",
                written.jsonObject.keys,
                "${command.simpleName} declares a field its conversion does not carry",
            )
        }
    }
}
