package io.nook.core.write

import io.nook.contract.ErrorCode
import io.nook.contract.StructuredErrorException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * set_item_blocked_by against the running service: whole-set replacement with
 * dedup, the leaves-only and same-project rules, self-block rejection, and
 * cycle rejection that leaves the store untouched.
 */
class WriteServiceBlockerTest {

    private companion object {
        val db by lazy { Database.connect(io.nook.core.db.EmbeddedPostgresSupport.freshMigratedDatabase()) }
        val service by lazy { WriteService(db) }
    }

    private fun assertFailsWithCode(code: ErrorCode, block: () -> Unit): StructuredErrorException {
        val failure = assertFailsWith<StructuredErrorException> { block() }
        assertEquals(code, failure.error.code)
        return failure
    }

    private fun reload(projectSlug: String, itemRef: String) = readItem(db, projectSlug, itemRef)

    @Test
    fun `the supplied set replaces the whole set, deduplicated, and empty clears it`() {
        val project = service.createProject("Replacement Semantics")
        service.createItem(project.slug, type = "task", name = "a")
        val itemB = service.createItem(project.slug, type = "task", name = "b")
        val itemC = service.createItem(project.slug, type = "task", name = "c")
        val itemD = service.createItem(project.slug, type = "task", name = "d")
        service.setItemBlockedBy(project.slug, "b", listOf("a"))

        val replaced = service.setItemBlockedBy(project.slug, "b", listOf("c", "c", "d"))
        assertEquals(setOf(itemC.id, itemD.id), replaced.blockedBy)

        val cleared = service.setItemBlockedBy(project.slug, "b", emptyList())
        assertEquals(emptySet(), cleared.blockedBy)

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.setItemBlockedBy(project.slug, "b", listOf("b"))
        }

        service.setItemBlockedBy(project.slug, "b", listOf("c", "d"))
        val resupplied = service.setItemBlockedBy(project.slug, "b", listOf("c", "d"))
        assertEquals(setOf(itemC.id, itemD.id), resupplied.blockedBy)

        val noOp = service.updateItem(project.slug, "b")
        assertEquals(itemB.name, noOp.name)
        assertEquals(itemB.slug, noOp.slug)
        assertEquals(itemB.status, noOp.status)
        assertEquals(setOf(itemC.id, itemD.id), noOp.blockedBy)
    }

    @Test
    fun `blockers must be same-project leaves that exist`() {
        val projectOne = service.createProject("Blocker Scope One")
        val projectTwo = service.createProject("Blocker Scope Two")
        service.createItem(projectOne.slug, type = "epic", name = "One epic")
        service.createItem(projectOne.slug, type = "task", name = "One leaf")
        service.createItem(projectTwo.slug, type = "epic", name = "Two epic")
        service.createItem(projectTwo.slug, type = "task", name = "Two leaf")

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.setItemBlockedBy(projectOne.slug, "one-leaf", listOf("one-epic"))
        }
        assertFailsWithCode(ErrorCode.NOT_FOUND) {
            service.setItemBlockedBy(projectOne.slug, "one-leaf", listOf("two-leaf"))
        }
        assertFailsWithCode(ErrorCode.NOT_FOUND) {
            service.setItemBlockedBy(projectOne.slug, "one-leaf", listOf(Uuid.random().toString()))
        }
    }

    @Test
    fun `a chain-closing set is rejected as a cycle and stores nothing`() {
        val project = service.createProject("Chain Refusal")
        service.createItem(project.slug, type = "task", name = "a")
        service.createItem(project.slug, type = "task", name = "b")
        service.createItem(project.slug, type = "task", name = "c")
        service.setItemBlockedBy(project.slug, "b", listOf("a"))
        service.setItemBlockedBy(project.slug, "c", listOf("b"))

        assertFailsWithCode(ErrorCode.CYCLE) {
            service.setItemBlockedBy(project.slug, "a", listOf("c"))
        }
        assertTrue(reload(project.slug, "a").blockedBy.isEmpty(), "the rejected call left an edge behind")
    }
}
