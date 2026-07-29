package io.nook.mcp

import io.modelcontextprotocol.spec.McpSchema
import io.nook.contract.ProjectItem
import io.nook.contract.Release
import io.nook.contract.aProject
import io.nook.contract.aRelease
import io.nook.contract.anItem
import io.nook.contract.catalogJson
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What every entity now carries about who wrote it, by the names it carries them under. */
private val WHO_WROTE_IT = listOf("createdBy", "updatedBy", "createdByAgent", "updatedByAgent")

/**
 * Who wrote a row, crossing the agent surface: on the entity, and equal to what
 * the core produced.
 *
 * The comparison is against the stand-in both adapters share, which is what makes
 * "each adapter's entity equals the other's" something these checks can say from
 * inside one module — two adapters measured against stand-ins free to differ would
 * have been shown nothing. What the two adapters do differ on is which entities
 * cross them: the agent surface offers the seven project-scoped operations, so
 * no project is read here, deliberately.
 */
class WhoWroteItCrossesTest {

    private val project = aProject()

    private val core = StandInCore().apply { projects += project }

    private val adapter = RunningAdapter(core)

    private val client = adapter.connectedAt(project.slug)

    @AfterTest
    fun letGo() {
        adapter.close()
    }

    private fun answering(entity: Any?, tool: String, vararg arguments: Pair<String, Any?>): String {
        core.answering = { entity }
        val answered = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(mapOf(*arguments)).build())
        assertEquals(false, answered.isError(), "the call was not served; it said: ${answered.said()}")
        return answered.said()
    }

    @Test
    fun `an item under an epic with two blockers arrives carrying who wrote it, equal to the core's own`() {
        val item = anItem(name = "Add search", slug = "add-search")
        val crossed = answering(item, "get_item", "ref" to "add-search")

        assertEquals(item, catalogJson.decodeFromString(ProjectItem.serializer(), crossed))
        WHO_WROTE_IT.forEach { field ->
            assertTrue(crossed.contains("\"$field\""), "an item crossed without $field; it said: $crossed")
        }
        assertTrue(item.blockedBy.isNotEmpty(), "the item this crossed with had no blockers to carry")
    }

    @Test
    fun `a release arrives carrying who wrote it, equal to the core's own`() {
        val release = aRelease()
        val crossed = answering(release, "create_release", "name" to "Autumn")

        assertEquals(release, catalogJson.decodeFromString(Release.serializer(), crossed))
        WHO_WROTE_IT.forEach { field ->
            assertTrue(crossed.contains("\"$field\""), "a release crossed without $field; it said: $crossed")
        }
    }

    @Test
    fun `a listing carries who wrote every item in it`() {
        val many = List(3) { anItem(name = "Item $it", slug = "item-$it") }
        val crossed = answering(many, "list_items")

        WHO_WROTE_IT.forEach { field ->
            assertEquals(
                many.size,
                Regex(Regex.escape("\"$field\"")).findAll(crossed).count(),
                "$field did not arrive on every item of the listing",
            )
        }
    }
}
