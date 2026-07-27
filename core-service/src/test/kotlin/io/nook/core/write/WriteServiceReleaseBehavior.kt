package io.nook.core.write

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.CreateRelease
import io.nook.contract.ErrorCode
import io.nook.contract.FieldChange
import io.nook.contract.ItemType
import io.nook.contract.ReleaseStatus
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateItem
import io.nook.contract.UpdateRelease
import io.nook.core.catalog.CatalogBehavior
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The release operations against the running service: creation with an
 * advisory (unpoliced) target date, updates under the shared partial-update
 * and status rules, and assignment that only ever applies to epics and is
 * never locked by any status.
 *
 * Assignment is a field of update_item rather than an operation, so these
 * exercise it there — including the case a field has and an operation did not:
 * an update that never mentions the release.
 */
abstract class WriteServiceReleaseBehavior : CatalogBehavior() {

    private fun assertFailsWithCode(code: ErrorCode, block: () -> Unit): StructuredErrorException {
        val failure = assertFailsWith<StructuredErrorException> { block() }
        assertEquals(code, failure.error.code)
        return failure
    }

    private fun assignedTo(releaseRef: String?) = UpdateItem(releaseRef = FieldChange.Set(releaseRef))

    @Test
    fun `a past target date is accepted, updates follow the vocabulary, and outside values are rejected`() {
        val project = service.createProject(CreateProject("Release Rules"))
        val release = service.createRelease(
            project.slug,
            CreateRelease("v1", targetDate = LocalDate.of(2001, 1, 1)),
        )
        assertEquals(LocalDate.of(2001, 1, 1), release.targetDate)

        val updated = service.updateRelease(
            project.slug, "v1",
            UpdateRelease(
                name = FieldChange.Set("v1 (final)"),
                status = FieldChange.Set("released"),
            ),
        )
        assertEquals("v1 (final)", updated.name)
        assertEquals(ReleaseStatus.RELEASED, updated.status)

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateRelease(project.slug, "v1", UpdateRelease(status = FieldChange.Set("shipped")))
        }
    }

    @Test
    fun `assignment applies to epics only and no status locks it`() {
        val project = service.createProject(CreateProject("Assignment Freedom"))
        val releaseOne = service.createRelease(project.slug, CreateRelease("R1"))
        val releaseTwo = service.createRelease(project.slug, CreateRelease("R2"))
        service.updateRelease(project.slug, "r1", UpdateRelease(status = FieldChange.Set("released")))
        val epic = service.createItem(project.slug, CreateItem(type = "epic", name = "The epic"))
        service.createItem(project.slug, CreateItem(type = "task", name = "The task"))

        val assigned = service.updateItem(project.slug, epic.slug, assignedTo("r1"))
        val reassigned = service.updateItem(project.slug, epic.slug, assignedTo("r2"))
        val unassigned = service.updateItem(project.slug, epic.slug, assignedTo(null))
        assertEquals(releaseOne.id, assigned.releaseId)
        assertEquals(releaseTwo.id, reassigned.releaseId)
        assertNull(unassigned.releaseId)

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(project.slug, "the-task", assignedTo("r1"))
        }
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(project.slug, "the-task", assignedTo(null))
        }

        val stillUnassigned = service.updateItem(project.slug, epic.slug, assignedTo(null))
        assertNull(stillUnassigned.releaseId)
    }

    @Test
    fun `an update that says nothing about the release leaves the assignment alone`() {
        val project = service.createProject(CreateProject("Assignment Kept"))
        val release = service.createRelease(project.slug, CreateRelease("R1"))
        val epic = service.createItem(project.slug, CreateItem(type = "epic", name = "The epic"))
        service.updateItem(project.slug, epic.slug, assignedTo("r1"))

        val renamed = service.updateItem(project.slug, epic.slug, UpdateItem(name = FieldChange.Set("Renamed")))

        assertEquals(release.id, renamed.releaseId)
    }

    @Test
    fun `the target must be an epic once the update lands, whatever it is now`() {
        // A leaf may be promoted and assigned in one command, because what the
        // rule asks about is the type the item ends up with. The other direction
        // is refused for the same reason: nothing may leave a leaf in a release.
        val project = service.createProject(CreateProject("Epic At The End"))
        val release = service.createRelease(project.slug, CreateRelease("R1"))
        val leaf = service.createItem(project.slug, CreateItem(type = "task", name = "A leaf"))

        val promoted = service.updateItem(
            project.slug, leaf.slug,
            assignedTo("r1").copy(type = FieldChange.Set("epic")),
        )
        assertEquals(ItemType.EPIC, promoted.type)
        assertEquals(release.id, promoted.releaseId)

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(
                project.slug, leaf.slug,
                assignedTo(null).copy(type = FieldChange.Set("task")),
            )
        }
    }

    @Test
    fun `release slugs are scoped per project`() {
        val projectOne = service.createProject(CreateProject("Scoped Releases One"))
        val projectTwo = service.createProject(CreateProject("Scoped Releases Two"))
        assertEquals("v1", service.createRelease(projectOne.slug, CreateRelease("v1")).slug)
        assertEquals("v1", service.createRelease(projectTwo.slug, CreateRelease("v1")).slug)
        assertFailsWithCode(ErrorCode.CONFLICT) {
            service.createRelease(projectOne.slug, CreateRelease("Other", slug = "v1"))
        }
    }
}

class WriteServiceReleaseInProcessTest : WriteServiceReleaseBehavior() {
    override val reach = Reach.IN_PROCESS
}

class WriteServiceReleaseAcrossConnectionTest : WriteServiceReleaseBehavior() {
    override val reach = Reach.ACROSS_THE_CONNECTION
}
