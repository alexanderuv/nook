package io.nook.core.write

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.ErrorCode
import io.nook.contract.FieldChange
import io.nook.contract.ItemType
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * update_item's blockedBy field: whole-set replacement with dedup, the
 * leaves-only and same-project rules, self-block rejection, and cycle rejection
 * that leaves the store untouched.
 *
 * Omitting the field is its own case, and the one a fold like this most easily
 * breaks: an update that says nothing about blockers must leave the set exactly
 * as it found it, not clear it.
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

    private fun blockedBy(vararg refs: String) = UpdateItem(blockedBy = FieldChange.Set(refs.toList()))

    private fun reload(projectSlug: String, itemRef: String) = readItem(db, projectSlug, itemRef)

    @Test
    fun `the supplied set replaces the whole set, deduplicated, and empty clears it`() {
        val project = service.createProject(CreateProject("Replacement Semantics"))
        service.createItem(project.slug, CreateItem(type = "task", name = "a"))
        val itemB = service.createItem(project.slug, CreateItem(type = "task", name = "b"))
        val itemC = service.createItem(project.slug, CreateItem(type = "task", name = "c"))
        val itemD = service.createItem(project.slug, CreateItem(type = "task", name = "d"))
        service.updateItem(project.slug, "b", blockedBy("a"))

        val replaced = service.updateItem(project.slug, "b", blockedBy("c", "c", "d"))
        assertEquals(setOf(itemC.id, itemD.id), replaced.blockedBy)

        val cleared = service.updateItem(project.slug, "b", blockedBy())
        assertEquals(emptySet(), cleared.blockedBy)

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(project.slug, "b", blockedBy("b"))
        }

        service.updateItem(project.slug, "b", blockedBy("c", "d"))
        val resupplied = service.updateItem(project.slug, "b", blockedBy("c", "d"))
        assertEquals(setOf(itemC.id, itemD.id), resupplied.blockedBy)

        val noOp = service.updateItem(project.slug, "b", UpdateItem())
        assertEquals(itemB.name, noOp.name)
        assertEquals(itemB.slug, noOp.slug)
        assertEquals(itemB.status, noOp.status)
        assertEquals(setOf(itemC.id, itemD.id), noOp.blockedBy)
    }

    @Test
    fun `an update that changes other fields leaves the blocker set alone`() {
        // The field's default is Keep, so every update that says nothing about
        // blockers is this case — including the ones every other test makes.
        val project = service.createProject(CreateProject("Untouched Blockers"))
        val blocker = service.createItem(project.slug, CreateItem(type = "task", name = "The blocker"))
        service.createItem(project.slug, CreateItem(type = "task", name = "The blocked"))
        service.updateItem(project.slug, "the-blocked", blockedBy("the-blocker"))

        val renamed = service.updateItem(
            project.slug, "the-blocked",
            UpdateItem(
                name = FieldChange.Set("Renamed"),
                status = FieldChange.Set("in_progress"),
                type = FieldChange.Set("bug"),
            ),
        )

        assertEquals(setOf(blocker.id), renamed.blockedBy)
        assertEquals(setOf(blocker.id), reload(project.slug, "the-blocked").blockedBy)
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
            service.updateItem(projectOne.slug, "one-leaf", blockedBy("one-epic"))
        }
        assertFailsWithCode(ErrorCode.NOT_FOUND) {
            service.updateItem(projectOne.slug, "one-leaf", blockedBy("two-leaf"))
        }
        assertFailsWithCode(ErrorCode.NOT_FOUND) {
            service.updateItem(projectOne.slug, "one-leaf", blockedBy(Uuid.random().toString()))
        }
    }

    @Test
    fun `the target must be a leaf once the update lands, whatever it is now`() {
        // Both fields of the same command have a say here, so the rule is read
        // against the type the item ends up with. Anything else would let one
        // command leave an epic holding blocker edges.
        val project = service.createProject(CreateProject("Leaf At The End"))
        service.createItem(project.slug, CreateItem(type = "task", name = "The blocker"))
        service.createItem(project.slug, CreateItem(type = "epic", name = "An epic"))
        service.createItem(project.slug, CreateItem(type = "task", name = "A leaf"))

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(project.slug, "a-leaf", blockedBy("the-blocker").copy(type = FieldChange.Set("epic")))
        }
        assertEquals(emptySet(), reload(project.slug, "a-leaf").blockedBy)

        val demoted = service.updateItem(
            project.slug, "an-epic",
            blockedBy("the-blocker").copy(type = FieldChange.Set("task")),
        )
        assertEquals(ItemType.TASK, demoted.type)
        assertEquals(setOf(reload(project.slug, "the-blocker").id), demoted.blockedBy)
    }

    @Test
    fun `a chain-closing set is rejected as a cycle and stores nothing`() {
        val project = service.createProject(CreateProject("Chain Refusal"))
        service.createItem(project.slug, CreateItem(type = "task", name = "a"))
        service.createItem(project.slug, CreateItem(type = "task", name = "b"))
        service.createItem(project.slug, CreateItem(type = "task", name = "c"))
        service.createItem(project.slug, CreateItem(type = "task", name = "d"))
        service.updateItem(project.slug, "b", blockedBy("a"))
        service.updateItem(project.slug, "c", blockedBy("b"))
        // `a` starts out already blocked, so "nothing was stored" is a claim about
        // this call and not about a set that was empty before it and after it.
        val before = service.updateItem(project.slug, "a", blockedBy("d"))

        assertFailsWithCode(ErrorCode.CYCLE) {
            service.updateItem(project.slug, "a", blockedBy("c"))
        }

        assertEquals(
            before.blockedBy,
            reload(project.slug, "a").blockedBy,
            "the rejected call must leave the whole set as it found it — neither adding nor clearing",
        )
        assertTrue(before.blockedBy.isNotEmpty(), "the set under test must not have been empty to begin with")
    }

    @Test
    fun `a rejected set takes the rest of its command down with it`() {
        // The fold's own hazard: blockers and the plain fields now travel in one
        // command, so a refusal owed to the blockers must roll back the name too.
        val project = service.createProject(CreateProject("All Or Nothing"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Alone"))

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(
                project.slug, "alone",
                blockedBy("alone").copy(name = FieldChange.Set("Renamed anyway?")),
            )
        }

        assertEquals("Alone", reload(project.slug, "alone").name)
    }
}
