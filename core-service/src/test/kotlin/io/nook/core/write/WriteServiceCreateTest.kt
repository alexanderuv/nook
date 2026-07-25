package io.nook.core.write

import io.nook.contract.ErrorCode
import io.nook.contract.ItemStatus
import io.nook.contract.ReleaseStatus
import io.nook.contract.StructuredErrorException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * The create operations against the running service on embedded PostgreSQL:
 * slug derivation and suffixing, containment, initial statuses, and the
 * rejections for unusable names, slugs, and types.
 */
class WriteServiceCreateTest {

    private companion object {
        val db by lazy { Database.connect(io.nook.core.db.EmbeddedPostgresSupport.freshMigratedDatabase()) }
        val service by lazy { WriteService(db) }
    }

    private fun assertFailsWithCode(code: ErrorCode, block: () -> Unit): StructuredErrorException {
        val failure = assertFailsWith<StructuredErrorException> { block() }
        assertEquals(code, failure.error.code)
        return failure
    }

    @Test
    fun `create_project derives the slug, returns the full entity, and suffixes a name collision`() {
        val project = service.createProject("Search Revamp!")
        assertEquals("search-revamp", project.slug)
        assertEquals("Search Revamp!", project.name)
        assertNull(project.artifactRepoUrl)

        val second = service.createProject("Search Revamp")
        assertEquals("search-revamp-2", second.slug)
    }

    @Test
    fun `a new release starts planned and a new item starts todo`() {
        val project = service.createProject("Fresh Statuses")
        val release = service.createRelease(project.slug, "v1")
        val item = service.createItem(project.slug, type = "task", name = "First task")
        assertEquals(ReleaseStatus.PLANNED, release.status)
        assertEquals(ItemStatus.TODO, item.status)
    }

    @Test
    fun `an unknown item type is rejected`() {
        val project = service.createProject("Typed")
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.createItem(project.slug, type = "story", name = "Not a thing")
        }
    }

    @Test
    fun `a leaf parents under an epic and a parentless leaf sits at project level`() {
        val project = service.createProject("Containment Happy")
        val epic = service.createItem(project.slug, type = "epic", name = "Search core")
        val task = service.createItem(project.slug, type = "task", name = "Index docs", parentRef = "search-core")
        val bug = service.createItem(project.slug, type = "bug", name = "Crash on empty query")
        assertEquals(epic.id, task.parentId)
        assertNull(bug.parentId)
    }

    @Test
    fun `a leaf cannot parent, an epic cannot be parented, and a leaf cannot join a release`() {
        val project = service.createProject("Containment Rejections")
        service.createItem(project.slug, type = "task", name = "Add search")
        service.createRelease(project.slug, "v1")
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.createItem(project.slug, type = "task", name = "Child", parentRef = "add-search")
        }
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.createItem(project.slug, type = "epic", name = "Parented epic", parentRef = "add-search")
        }
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.createItem(project.slug, type = "task", name = "Released task", releaseRef = "v1")
        }
    }

    @Test
    fun `derived slugs take suffixes in sequence`() {
        val project = service.createProject("Suffix Sequence")
        val slugs = (1..3).map { service.createItem(project.slug, type = "task", name = "Add search").slug }
        assertEquals(listOf("add-search", "add-search-2", "add-search-3"), slugs)
    }

    @Test
    fun `derivation skips over an explicitly claimed suffix to the first free one`() {
        val project = service.createProject("Suffix Gap")
        service.createItem(project.slug, type = "task", name = "Add search")
        service.createItem(project.slug, type = "task", name = "Claimed", slug = "add-search-2")
        val third = service.createItem(project.slug, type = "task", name = "Add search")
        assertEquals("add-search-3", third.slug)
    }

    @Test
    fun `unusable names and slugs are rejected, and an explicit slug saves an unusable name`() {
        val project = service.createProject("Unusable Input")
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.createItem(project.slug, type = "task", name = "???")
        }
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.createItem(project.slug, type = "task", name = "")
        }
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.createItem(project.slug, type = "task", name = "Cased", slug = "Add-Search")
        }
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.createItem(project.slug, type = "task", name = "Uuid slug", slug = Uuid.random().toString())
        }
        val saved = service.createItem(project.slug, type = "task", name = "???", slug = "q3-spike")
        assertEquals("q3-spike", saved.slug)
    }
}
