package io.nook.contract

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.serialization.SerializationException

/**
 * The values the entities and the listing filter are made of, across the wire
 * and back — the vocabularies as the words a caller writes, the two time types
 * whose conversions are written by hand, and the filter's five parts.
 *
 * The filter needs no conversion of its own: a part nobody is filtering on is
 * absent from the text, while a part with no values is an empty list, and those
 * are already different things. What the checks below hold is that they stay
 * different, since folding one into the other would turn a caller's mistake
 * into a silent listing of everything.
 */
class ValueCrossingTest {

    private inline fun <reified T> crossed(value: T): T =
        catalogJson.decodeFromString(catalogJson.encodeToString(value))

    @Test
    fun `a vocabulary crosses as the label a caller writes, never as its Kotlin name`() {
        assertEquals(""""task"""", catalogJson.encodeToString(ItemType.TASK))
        assertEquals(""""in_progress"""", catalogJson.encodeToString(ItemStatus.IN_PROGRESS))
        assertEquals(""""planned"""", catalogJson.encodeToString(ReleaseStatus.PLANNED))
        assertEquals(""""validation_failed"""", catalogJson.encodeToString(ErrorCode.VALIDATION_FAILED))

        ItemType.entries.forEach { assertEquals(it, crossed(it)) }
        ItemStatus.entries.forEach { assertEquals(it, crossed(it)) }
        ReleaseStatus.entries.forEach { assertEquals(it, crossed(it)) }
        ErrorCode.entries.forEach { assertEquals(it, crossed(it)) }
    }

    @Test
    fun `a word outside a vocabulary is refused, and the refusal names the vocabulary`() {
        val refused = assertFailsWith<SerializationException> {
            catalogJson.decodeFromString<ItemStatus>(""""blocked"""")
        }
        assertEquals(
            "\"blocked\" is not an item status; the item statuses are todo, in_progress, done, cancelled",
            refused.message,
        )
    }

    @Test
    fun `a moment names the same instant afterwards, to the microsecond`() {
        val moment = Instant.parse("2026-07-27T02:27:11.357547Z")
        assertEquals(""""2026-07-27T02:27:11.357547Z"""", catalogJson.encodeToString(InstantSerializer, moment))
        assertEquals(moment, catalogJson.decodeFromString(InstantSerializer, catalogJson.encodeToString(InstantSerializer, moment)))

        // A moment on a whole second prints without a fraction, and still names
        // the same instant when it comes back.
        val onTheSecond = Instant.parse("2026-07-27T02:27:11Z")
        assertEquals(
            onTheSecond,
            catalogJson.decodeFromString(InstantSerializer, catalogJson.encodeToString(InstantSerializer, onTheSecond)),
        )
    }

    @Test
    fun `a calendar date crosses as a day, carrying no time and no zone`() {
        val date = LocalDate.of(2026, 12, 24)
        assertEquals(""""2026-12-24"""", catalogJson.encodeToString(LocalDateSerializer, date))
        assertEquals(date, catalogJson.decodeFromString(LocalDateSerializer, catalogJson.encodeToString(LocalDateSerializer, date)))
    }

    @Test
    fun `text arrives exactly as it was sent`() {
        val awkward = "Søk 🔍 épico\nsecond line\twith a tab and \"quotation marks\""
        assertEquals(awkward, crossed(CreateItem(type = "task", name = awkward)).name)
    }

    @Test
    fun `an absent field stays absent rather than arriving as empty text`() {
        val bare = CreateProject(name = "Bare")
        assertEquals("""{"name":"Bare"}""", catalogJson.encodeToString(bare))
        assertEquals(bare, crossed(bare))
        assertNotEquals(CreateProject(name = "Bare", description = ""), crossed(bare))
    }

    @Test
    fun `not filtering on a part and filtering on no values stay different things`() {
        assertEquals("{}", catalogJson.encodeToString(ItemFilter()))
        assertEquals("""{"types":[]}""", catalogJson.encodeToString(ItemFilter(types = emptyList())))
        assertNotEquals(crossed(ItemFilter()), crossed(ItemFilter(types = emptyList())))

        listOf(
            ItemFilter(types = emptyList()),
            ItemFilter(statuses = emptyList()),
            ItemFilter(parents = emptyList()),
            ItemFilter(releases = emptyList()),
        ).forEach { withNoValues ->
            assertEquals(withNoValues, crossed(withNoValues))
            assertNotEquals(ItemFilter(), crossed(withNoValues))
        }
    }

    @Test
    fun `each of the filter's five parts survives the crossing`() {
        listOf(
            ItemFilter(),
            ItemFilter(types = listOf("task", "bug", "task")),
            ItemFilter(statuses = listOf("todo")),
            ItemFilter(parents = listOf(ParentFilter.NoEpic)),
            ItemFilter(parents = listOf(ParentFilter.Epic("search-core"))),
            ItemFilter(parents = listOf(ParentFilter.Epic("search-core"), ParentFilter.NoEpic)),
            ItemFilter(releases = listOf("v1", "v2")),
            ItemFilter(heldUp = false),
            ItemFilter(heldUp = true),
            ItemFilter(
                types = listOf("task", "bug", "chore"),
                statuses = listOf("todo"),
                parents = listOf(ParentFilter.Epic("search-core")),
                releases = listOf("v1"),
                heldUp = false,
            ),
        ).forEach { filter -> assertEquals(filter, crossed(filter)) }
    }

    @Test
    fun `a parent value is an epic's reference, or nothing at all for no epic above`() {
        assertEquals(
            """{"parents":["search-core",null]}""",
            catalogJson.encodeToString(
                ItemFilter(parents = listOf(ParentFilter.Epic("search-core"), ParentFilter.NoEpic)),
            ),
        )
    }
}
