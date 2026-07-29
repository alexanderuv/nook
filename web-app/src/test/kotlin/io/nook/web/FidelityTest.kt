package io.nook.web

import io.nook.contract.CatalogOperation
import io.nook.contract.CreateItem
import io.nook.contract.DEFAULT_WAIT_LIMIT
import io.nook.contract.FieldChange
import io.nook.contract.Invocation
import io.nook.contract.ItemFilter
import io.nook.contract.ParentFilter
import io.nook.contract.ProjectItem
import io.nook.contract.Release
import io.nook.contract.RpcReply
import io.nook.contract.UpdateItem
import io.nook.contract.aRelease
import io.nook.contract.anItem
import io.nook.contract.catalogJson
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.uuid.Uuid
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What a caller supplies reaching the core untouched, and what the core
 * produced coming back whole.
 *
 * Every comparison here is of whole values rather than of a chosen few fields,
 * in both directions: a command that gains a field is covered without this
 * being edited, and an entity that gains one is too. Nothing is trimmed,
 * reordered, filled in, deduplicated or dropped on the way in, and nothing is
 * rendered on the way out.
 *
 * What the values are read off is the core at the far end of the connection,
 * which is where they land — so what is being held to account is this surface
 * *and* the crossing it sits on, rather than this surface alone.
 */
class FidelityTest {

    private val both = CoreAndApi()

    private val core = both.core

    @AfterTest
    fun letGo() {
        both.close()
    }

    private fun call(method: String, params: JsonObjectBuilder.() -> Unit = {}): String =
        rawCall(method, catalogJson.encodeToString(JsonObject.serializer(), buildJsonObject(params)))

    /** What the core was invoked with when [body] was sent to the app, and nothing else. */
    private fun reaching(body: String): Invocation {
        core.invocations.clear()
        assertIs<RpcReply.Answered>(both.answer(body), body)
        return core.invocations.single()
    }

    private fun updateReaching(supplied: JsonObjectBuilder.() -> Unit): UpdateItem =
        reaching(
            call("update_item") {
                put("project", PROJECT)
                put("ref", "add-search")
                supplied()
            },
        ).values.last() as UpdateItem

    private fun filterReaching(supplied: JsonObjectBuilder.() -> Unit): ItemFilter =
        reaching(
            call("list_items") {
                put("project", PROJECT)
                supplied()
            },
        ).values.last() as ItemFilter

    @Test
    fun `an update says leave this alone, set this, and change nothing, and the three stay apart`() {
        assertEquals(UpdateItem(name = FieldChange.Set("Renamed")), updateReaching { put("name", "Renamed") })

        // Naming the field with nothing in it is not the same as never naming
        // it, all the way down to the store.
        assertEquals(
            UpdateItem(description = FieldChange.Set(null)),
            updateReaching { put("description", JsonNull) },
        )
        assertEquals(UpdateItem(description = FieldChange.Set("")), updateReaching { put("description", "") })

        // And an update naming no field at all reaches the core naming none,
        // rather than being refused or dropped before it is sent.
        assertEquals(UpdateItem(), updateReaching { })
    }

    @Test
    fun `a blocker list keeps its duplicates, and an empty one is not a list nobody supplied`() {
        assertEquals(
            UpdateItem(blockedBy = FieldChange.Set(listOf("index-docs", "index-docs"))),
            updateReaching { putJsonArray("blockedBy") { add("index-docs"); add("index-docs") } },
        )
        assertEquals(
            UpdateItem(blockedBy = FieldChange.Set(emptyList())),
            updateReaching { putJsonArray("blockedBy") { } },
        )
    }

    @Test
    fun `each filter part reaches the core alone, and all five together`() {
        assertEquals(ItemFilter(types = listOf("task")), filterReaching { putJsonArray("types") { add("task") } })
        assertEquals(
            ItemFilter(statuses = listOf("todo")),
            filterReaching { putJsonArray("statuses") { add("todo") } },
        )
        assertEquals(
            ItemFilter(parents = listOf(ParentFilter.Epic("the-epic"), ParentFilter.NoEpic)),
            filterReaching { putJsonArray("parents") { add("the-epic"); add(JsonNull) } },
        )
        assertEquals(
            ItemFilter(releases = listOf("autumn")),
            filterReaching { putJsonArray("releases") { add("autumn") } },
        )
        assertEquals(ItemFilter(heldUp = false), filterReaching { put("heldUp", false) })

        assertEquals(
            ItemFilter(
                types = listOf("task", "bug", "chore"),
                statuses = listOf("todo"),
                parents = listOf(ParentFilter.NoEpic),
                releases = listOf("autumn"),
                heldUp = false,
            ),
            filterReaching {
                putJsonArray("types") { add("task"); add("bug"); add("chore") }
                putJsonArray("statuses") { add("todo") }
                putJsonArray("parents") { add(JsonNull) }
                putJsonArray("releases") { add("autumn") }
                put("heldUp", false)
            },
        )
    }

    @Test
    fun `a filter part supplied with no values reaches the core that way`() {
        // "Do not filter on this" and "match any of nothing" are different
        // things, and turning the second quietly into the first here would be
        // this surface deciding something the core refuses.
        assertEquals(ItemFilter(types = emptyList()), filterReaching { putJsonArray("types") { } })
        assertEquals(ItemFilter(types = null), filterReaching { })
    }

    @Test
    fun `text a caller wrote arrives as they wrote it, and a reference nobody can resolve arrives as written`() {
        val name = "検索 🔍 revamp"
        val description = "Two lines,\nand \"quotation marks\", and a tab\there"
        assertEquals(
            CreateItem(type = "task", name = name, description = description),
            reaching(
                call("create_item") {
                    put("project", PROJECT)
                    put("type", "task")
                    put("name", name)
                    put("description", description)
                },
            ).values.single(),
        )

        // A character short of a well-formed id. What it means is the core's to
        // decide, so it has to arrive unchanged.
        val nearlyAnId = Uuid.random().toString().dropLast(1)
        assertEquals(
            listOf(nearlyAnId),
            reaching(call("get_item") { put("project", PROJECT); put("ref", nearlyAnId) }).values,
        )
    }

    @Test
    fun `the project a call acts inside arrives as written, and the four that act on the instance name none`() {
        assertEquals(PROJECT, reaching(call("get_item") { put("project", PROJECT); put("ref", "add-search") }).project)
        assertNull(reaching(call("list_projects")).project)
    }

    @Test
    fun `each entity comes back equal to the one the core produced`() {
        val item = anItem(name = "Add search 🔍", slug = "add-search")
        core.answering = { item }
        assertEquals(
            catalogJson.encodeToJsonElement(ProjectItem.serializer(), item),
            assertIs<RpcReply.Answered>(both.answer(call("get_item") { put("project", PROJECT); put("ref", "x") }))
                .result,
        )

        val release = aRelease()
        core.answering = { release }
        assertEquals(
            catalogJson.encodeToJsonElement(Release.serializer(), release),
            assertIs<RpcReply.Answered>(
                both.answer(call("create_release") { put("project", PROJECT); put("name", "Autumn") }),
            ).result,
        )
    }

    @Test
    fun `both deletes report success carrying no entity, and stay apart from a failure`() {
        core.answering = { null }
        listOf(
            call("delete_item") { put("project", PROJECT); put("ref", "add-search") },
            call("delete_project") { put("ref", PROJECT) },
        ).forEach { body ->
            val answered = assertIs<RpcReply.Answered>(both.answer(body), body)
            assertNull(answered.result, "a delete carried an entity")
        }

        // Nothing about a success looks like being turned down, which is what
        // makes "no entity" readable as the success it is.
        core.answerNormally()
        assertIs<RpcReply.Failed>(both.answer(call("get_item") { put("project", PROJECT); put("ref", NOT_THERE) }))
    }

    @Test
    fun `a listing of five thousand items arrives whole, in one reply, in the core's own order`() {
        val many = List(5000) { anItem(name = "Item $it", slug = "item-$it", id = idOf(it)) }
        core.answering = { many }

        val began = System.nanoTime()
        val answered = assertIs<RpcReply.Answered>(both.answer(call("list_items") { put("project", PROJECT) }))
        val took = (System.nanoTime() - began).nanoseconds

        // Inside the wait limit, said out loud rather than left to the fact that
        // the call came back at all: a listing that only just made it would pass
        // either way, and the point is that it does not have to only just.
        assertTrue(took < DEFAULT_WAIT_LIMIT, "the listing took $took, and a call waits at most $DEFAULT_WAIT_LIMIT")
        assertEquals(5000, assertIs<JsonArray>(answered.result).size)
        assertEquals(
            catalogJson.encodeToJsonElement(ListSerializer(ProjectItem.serializer()), many),
            answered.result,
            "the listing did not arrive whole and in the order the core produced",
        )
        assertEquals(CatalogOperation.LIST_ITEMS, core.invocations.last().operation)
    }

    private companion object {

        const val PROJECT = "search-revamp"

        fun idOf(which: Int): Uuid = Uuid.parse("cccccccc-0000-0000-0000-%012d".format(which))
    }
}
