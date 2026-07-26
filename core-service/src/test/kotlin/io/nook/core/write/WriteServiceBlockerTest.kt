package io.nook.core.write

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.ErrorCode
import io.nook.contract.SetItemBlockedBy
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateItem
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
        val project = service.createProject(CreateProject("Replacement Semantics"))
        service.createItem(project.slug, CreateItem(type = "task", name = "a"))
        val itemB = service.createItem(project.slug, CreateItem(type = "task", name = "b"))
        val itemC = service.createItem(project.slug, CreateItem(type = "task", name = "c"))
        val itemD = service.createItem(project.slug, CreateItem(type = "task", name = "d"))
        service.setItemBlockedBy(project.slug, "b", SetItemBlockedBy(listOf("a")))

        val replaced = service.setItemBlockedBy(project.slug, "b", SetItemBlockedBy(listOf("c", "c", "d")))
        assertEquals(setOf(itemC.id, itemD.id), replaced.blockedBy)

        val cleared = service.setItemBlockedBy(project.slug, "b", SetItemBlockedBy(emptyList()))
        assertEquals(emptySet(), cleared.blockedBy)

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.setItemBlockedBy(project.slug, "b", SetItemBlockedBy(listOf("b")))
        }

        service.setItemBlockedBy(project.slug, "b", SetItemBlockedBy(listOf("c", "d")))
        val resupplied = service.setItemBlockedBy(project.slug, "b", SetItemBlockedBy(listOf("c", "d")))
        assertEquals(setOf(itemC.id, itemD.id), resupplied.blockedBy)

        val noOp = service.updateItem(project.slug, "b", UpdateItem())
        assertEquals(itemB.name, noOp.name)
        assertEquals(itemB.slug, noOp.slug)
        assertEquals(itemB.status, noOp.status)
        assertEquals(setOf(itemC.id, itemD.id), noOp.blockedBy)
    }

    @Test
    fun `blockers must be same-project leaves that exist`() {
        val projectOne = service.createProject(CreateProject("Blocker Scope One"))
        val projectTwo = service.createProject(CreateProject("Blocker Scope Two"))
        service.createItem(projectOne.slug, CreateItem(type = "epic", name = "One epic"))
        service.createItem(projectOne.slug, CreateItem(type = "task", name = "One leaf"))
        service.createItem(projectTwo.slug, CreateItem(type = "epic", name = "Two epic"))
        service.createItem(projectTwo.slug, CreateItem(type = "task", name = "Two leaf"))

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.setItemBlockedBy(projectOne.slug, "one-leaf", SetItemBlockedBy(listOf("one-epic")))
        }
        assertFailsWithCode(ErrorCode.NOT_FOUND) {
            service.setItemBlockedBy(projectOne.slug, "one-leaf", SetItemBlockedBy(listOf("two-leaf")))
        }
        assertFailsWithCode(ErrorCode.NOT_FOUND) {
            service.setItemBlockedBy(
                projectOne.slug, "one-leaf",
                SetItemBlockedBy(listOf(Uuid.random().toString())),
            )
        }
    }

    @Test
    fun `a chain-closing set is rejected as a cycle and stores nothing`() {
        val project = service.createProject(CreateProject("Chain Refusal"))
        service.createItem(project.slug, CreateItem(type = "task", name = "a"))
        service.createItem(project.slug, CreateItem(type = "task", name = "b"))
        service.createItem(project.slug, CreateItem(type = "task", name = "c"))
        service.createItem(project.slug, CreateItem(type = "task", name = "d"))
        service.setItemBlockedBy(project.slug, "b", SetItemBlockedBy(listOf("a")))
        service.setItemBlockedBy(project.slug, "c", SetItemBlockedBy(listOf("b")))
        // `a` starts out already blocked, so "nothing was stored" is a claim about
        // this call and not about a set that was empty before it and after it.
        val before = service.setItemBlockedBy(project.slug, "a", SetItemBlockedBy(listOf("d")))

        assertFailsWithCode(ErrorCode.CYCLE) {
            service.setItemBlockedBy(project.slug, "a", SetItemBlockedBy(listOf("c")))
        }

        assertEquals(
            before.blockedBy,
            reload(project.slug, "a").blockedBy,
            "the rejected call must leave the whole set as it found it — neither adding nor clearing",
        )
        assertTrue(before.blockedBy.isNotEmpty(), "the set under test must not have been empty to begin with")
    }
}
