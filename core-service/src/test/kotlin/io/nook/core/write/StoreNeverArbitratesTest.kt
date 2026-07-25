package io.nook.core.write

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.CreateRelease
import io.nook.contract.ErrorCode
import io.nook.contract.FieldChange
import io.nook.contract.SetItemBlockedBy
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateItem
import io.nook.contract.UpdateRelease
import io.nook.core.db.EmbeddedPostgresSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Validation decides, never the database. Each named rule the schema enforces
 * that a caller can actually reach is exercised here through the service, and
 * every one must come back as a structured error the caller can act on. If any
 * of these ever arrives as a raw database failure instead, the write path has
 * started leaning on the store to police it — and the store can only report
 * which rule broke in its own dialect, which the service deliberately cannot
 * read.
 *
 * The remaining rules cannot be reached from any input: the identity and
 * ownership uniqueness rules are keyed on identifiers this service generates
 * itself, and the document rules belong to an entity no operation writes yet.
 */
class StoreNeverArbitratesTest {

    private companion object {
        val db by lazy { Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase()) }
        val service by lazy { WriteService(db) }
    }

    private fun assertFailsWithCode(code: ErrorCode, block: () -> Unit) {
        val failure = assertFailsWith<StructuredErrorException> { block() }
        assertEquals(code, failure.error.code)
    }

    @Test
    fun `a handle already taken in its scope is refused before the store sees it`() {
        val project = service.createProject(CreateProject(name = "Handles Are Ours", slug = "handles-are-ours"))
        assertFailsWithCode(ErrorCode.CONFLICT) {
            service.createProject(CreateProject(name = "Another", slug = "handles-are-ours"))
        }

        service.createItem(project.slug, CreateItem(type = "task", name = "Add search", slug = "add-search"))
        assertFailsWithCode(ErrorCode.CONFLICT) {
            service.createItem(project.slug, CreateItem(type = "bug", name = "Other", slug = "add-search"))
        }
        service.createItem(project.slug, CreateItem(type = "task", name = "Second", slug = "second"))
        assertFailsWithCode(ErrorCode.CONFLICT) {
            service.updateItem(project.slug, "second", UpdateItem(slug = FieldChange.Set("add-search")))
        }

        service.createRelease(project.slug, CreateRelease(name = "One", slug = "v1"))
        assertFailsWithCode(ErrorCode.CONFLICT) {
            service.createRelease(project.slug, CreateRelease(name = "Two", slug = "v1"))
        }
        service.createRelease(project.slug, CreateRelease(name = "Three", slug = "v3"))
        assertFailsWithCode(ErrorCode.CONFLICT) {
            service.updateRelease(project.slug, "v3", UpdateRelease(slug = FieldChange.Set("v1")))
        }
    }

    @Test
    fun `a reference into another project is refused before the store sees it`() {
        val here = service.createProject(CreateProject(name = "Reference Scope Here"))
        val elsewhere = service.createProject(CreateProject(name = "Reference Scope Elsewhere"))
        val foreignEpic = service.createItem(elsewhere.slug, CreateItem(type = "epic", name = "Foreign epic"))
        val foreignRelease = service.createRelease(elsewhere.slug, CreateRelease(name = "Foreign release"))
        service.createItem(here.slug, CreateItem(type = "epic", name = "Local epic"))
        service.createItem(here.slug, CreateItem(type = "task", name = "Local leaf"))

        assertFailsWithCode(ErrorCode.NOT_FOUND) {
            service.createItem(
                here.slug,
                CreateItem(type = "task", name = "Child", parentRef = foreignEpic.id.toString()),
            )
        }
        assertFailsWithCode(ErrorCode.NOT_FOUND) {
            service.createItem(
                here.slug,
                CreateItem(type = "epic", name = "Assigned", releaseRef = foreignRelease.id.toString()),
            )
        }
        assertFailsWithCode(ErrorCode.NOT_FOUND) {
            service.setItemBlockedBy(
                here.slug, "local-leaf",
                SetItemBlockedBy(listOf(Uuid.random().toString())),
            )
        }
    }

    @Test
    fun `an item blocking itself is refused before the store sees it`() {
        val project = service.createProject(CreateProject(name = "No Self Block"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Alone"))

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.setItemBlockedBy(project.slug, "alone", SetItemBlockedBy(listOf("alone")))
        }
    }

    @Test
    fun `the same blocker named twice collapses instead of colliding in the store`() {
        val project = service.createProject(CreateProject(name = "Repeated Blocker"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Blocker"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Blocked"))

        val blocked = service.setItemBlockedBy(
            project.slug, "blocked",
            SetItemBlockedBy(listOf("blocker", "blocker")),
        )
        assertEquals(1, blocked.blockedBy.size)
    }
}
