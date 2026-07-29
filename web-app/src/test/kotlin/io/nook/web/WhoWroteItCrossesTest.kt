package io.nook.web

import io.nook.contract.Project
import io.nook.contract.ProjectItem
import io.nook.contract.Release
import io.nook.contract.RpcReply
import io.nook.contract.aProject
import io.nook.contract.aRelease
import io.nook.contract.anItem
import io.nook.contract.catalogJson
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

/** What every entity now carries about who wrote it, by the names it carries them under. */
private val WHO_WROTE_IT = listOf("createdBy", "updatedBy", "createdByAgent", "updatedByAgent")

/** And the one a project carries beyond them: whose it is, as against who wrote its row. */
private const val WHOSE_IT_IS = "ownerSubject"

/**
 * Who wrote a row, crossing the web API: on the entity, and equal to what the
 * core produced.
 *
 * The comparison is against the stand-in both doors share — the same entities
 * the agent surface's own checks compare against — which is what makes "each
 * door's entity equals the other's" something two modules can each say for
 * themselves. Every entity kind crosses here, the project included, which is
 * where a project is read: the agent surface offers no project read at all.
 */
class WhoWroteItCrossesTest {

    private val doors = BothDoors()

    private val core = doors.core

    @AfterTest
    fun letGo() {
        doors.close()
    }

    private fun answering(entity: Any?, body: String): JsonObject {
        core.answering = { entity }
        return assertIs<JsonObject>(assertIs<RpcReply.Answered>(doors.answer(body)).result, body)
    }

    @Test
    fun `a project arrives carrying who wrote it and whose it is, equal to the core's own`() {
        val project = aProject()
        val crossed = answering(project, rawCall("get_project", """{"ref":"search-revamp"}"""))

        assertEquals(project, catalogJson.decodeFromJsonElement(Project.serializer(), crossed))
        (WHO_WROTE_IT + WHOSE_IT_IS).forEach { field ->
            assertTrue(crossed.containsKey(field), "a project crossed without $field; it carried ${crossed.keys}")
        }
    }

    @Test
    fun `an item under an epic with two blockers arrives carrying who wrote it, equal to the core's own`() {
        val item = anItem(name = "Add search", slug = "add-search")
        val crossed = answering(item, rawCall("get_item", """{"project":"search-revamp","ref":"add-search"}"""))

        assertEquals(item, catalogJson.decodeFromJsonElement(ProjectItem.serializer(), crossed))
        WHO_WROTE_IT.forEach { field ->
            assertTrue(crossed.containsKey(field), "an item crossed without $field; it carried ${crossed.keys}")
        }
        assertTrue(
            crossed.containsKey(WHOSE_IT_IS).not(),
            "an item carried an owner, which only a project has",
        )
    }

    @Test
    fun `a release arrives carrying who wrote it, equal to the core's own`() {
        val release = aRelease()
        val crossed = answering(release, rawCall("create_release", """{"project":"search-revamp","name":"Autumn"}"""))

        assertEquals(release, catalogJson.decodeFromJsonElement(Release.serializer(), crossed))
        WHO_WROTE_IT.forEach { field ->
            assertTrue(crossed.containsKey(field), "a release crossed without $field; it carried ${crossed.keys}")
        }
    }
}
