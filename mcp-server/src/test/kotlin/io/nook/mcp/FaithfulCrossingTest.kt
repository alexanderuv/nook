package io.nook.mcp

import io.modelcontextprotocol.spec.McpSchema
import io.nook.contract.CreateItem
import io.nook.contract.FieldChange
import io.nook.contract.ItemFilter
import io.nook.contract.ParentFilter
import io.nook.contract.UpdateItem
import io.nook.contract.aProject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What an agent supplies, against what the core is invoked with.
 *
 * Every check compares whole values rather than field by field, so a command
 * that gains a field is covered by the comparison it already has: a field
 * dropped on the way, or filled in on the agent's behalf, makes the two values
 * differ without anything here being edited.
 */
class FaithfulCrossingTest {

    private val project = aProject()

    private val core = StandInCore().apply { projects += project }

    private val adapter = RunningAdapter(core)

    private val client = adapter.connectedAt(project.slug)

    @AfterTest
    fun letGo() {
        adapter.close()
    }

    /** Calls [tool] and hands back the values the core was invoked with, the project aside. */
    private fun supplying(tool: String, vararg arguments: Pair<String, Any?>): List<Any?> {
        core.invocations.clear()
        val answered = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(mapOf(*arguments)).build())
        assertEquals(false, answered.isError(), "the call was not served; it said: ${answered.said()}")
        return core.toolCalls.single().values
    }

    @Test
    fun `a name carrying emoji and non-Latin script reaches the core as it was written`() {
        val name = "🔍 検索を直す — naïve façade"

        assertEquals(
            listOf(CreateItem(type = "task", name = name)),
            supplying("create_item", "type" to "task", "name" to name),
        )
    }

    @Test
    fun `a description carrying line breaks and quotation marks reaches the core as it was written`() {
        val description = "Two lines,\nand \"quotation marks\", and a tab\there — plus a trailing space "

        assertEquals(
            listOf(CreateItem(type = "bug", name = "Fix it", description = description)),
            supplying("create_item", "type" to "bug", "name" to "Fix it", "description" to description),
        )
    }

    @Test
    fun `a blocker list keeps its duplicate, and one supplied empty arrives empty`() {
        val twice = listOf("add-search", "index-rebuild", "add-search")

        assertEquals(
            listOf("search-core", UpdateItem(blockedBy = FieldChange.Set(twice))),
            supplying("update_item", "ref" to "search-core", "blockedBy" to twice),
            "a blocker list was deduplicated or reordered on the way",
        )
        assertEquals(
            listOf("search-core", UpdateItem(blockedBy = FieldChange.Set(emptyList()))),
            supplying("update_item", "ref" to "search-core", "blockedBy" to emptyList<String>()),
            "an empty blocker list was mistaken for no list having been supplied",
        )
    }

    @Test
    fun `a reference that is nearly an id reaches the core as written`() {
        // One group short of an id, and a legal handle. What it means is the
        // core's to decide, so nothing here may decide it first.
        val nearly = "3f2a8c1e-4b6d-4b0a-9f3e"

        assertEquals(listOf(nearly), supplying("get_item", "ref" to nearly))
    }

    @Test
    fun `an update naming no field at all reaches the core naming no field`() {
        assertEquals(
            listOf("search-core", UpdateItem()),
            supplying("update_item", "ref" to "search-core"),
            "an update with nothing in it was refused, discarded, or filled in",
        )
    }

    @Test
    fun `each part of the listing filter reaches the core on its own`() {
        val eachPart = listOf(
            arrayOf("types" to listOf("epic", "task")) to ItemFilter(types = listOf("epic", "task")),
            arrayOf("statuses" to listOf("todo", "done")) to ItemFilter(statuses = listOf("todo", "done")),
            arrayOf("parents" to listOf("search-core", null)) to
                ItemFilter(parents = listOf(ParentFilter.Epic("search-core"), ParentFilter.NoEpic)),
            arrayOf("releases" to listOf("autumn")) to ItemFilter(releases = listOf("autumn")),
            arrayOf("heldUp" to false) to ItemFilter(heldUp = false),
        )

        eachPart.forEach { (supplied, expected) ->
            assertEquals(listOf(expected), supplying("list_items", *supplied), "the ${supplied.first().first} part")
        }
    }

    @Test
    fun `all five parts of the listing filter reach the core together`() {
        assertEquals(
            listOf(
                ItemFilter(
                    types = listOf("task"),
                    statuses = listOf("todo"),
                    parents = listOf(ParentFilter.Epic("search-core")),
                    releases = listOf("autumn"),
                    heldUp = false,
                ),
            ),
            supplying(
                "list_items",
                "types" to listOf("task"),
                "statuses" to listOf("todo"),
                "parents" to listOf("search-core"),
                "releases" to listOf("autumn"),
                "heldUp" to false,
            ),
        )
    }

    @Test
    fun `a filter part supplied with no values reaches the core with no values`() {
        assertEquals(
            listOf(ItemFilter(types = emptyList())),
            supplying("list_items", "types" to emptyList<String>()),
            "a part with no values was quietly turned into not filtering on that part",
        )
    }
}
