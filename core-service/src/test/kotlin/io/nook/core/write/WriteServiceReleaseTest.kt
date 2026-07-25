package io.nook.core.write

import io.nook.contract.AssignEpicToRelease
import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.CreateRelease
import io.nook.contract.ErrorCode
import io.nook.contract.FieldChange
import io.nook.contract.ReleaseStatus
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateRelease
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * The release operations against the running service: creation with an
 * advisory (unpoliced) target date, updates under the shared partial-update
 * and status rules, and assignment that only ever applies to epics and is
 * never locked by any status.
 */
class WriteServiceReleaseTest {

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

        val assigned = service.assignEpicToRelease(project.slug, epic.slug, AssignEpicToRelease("r1"))
        val reassigned = service.assignEpicToRelease(project.slug, epic.slug, AssignEpicToRelease("r2"))
        val unassigned = service.assignEpicToRelease(project.slug, epic.slug, AssignEpicToRelease(null))
        assertEquals(releaseOne.id, assigned.releaseId)
        assertEquals(releaseTwo.id, reassigned.releaseId)
        assertNull(unassigned.releaseId)

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.assignEpicToRelease(project.slug, "the-task", AssignEpicToRelease("r1"))
        }
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.assignEpicToRelease(project.slug, "the-task", AssignEpicToRelease(null))
        }

        val stillUnassigned = service.assignEpicToRelease(project.slug, epic.slug, AssignEpicToRelease(null))
        assertNull(stillUnassigned.releaseId)
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
