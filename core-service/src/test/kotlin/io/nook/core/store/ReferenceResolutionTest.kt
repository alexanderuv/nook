package io.nook.core.store

import io.nook.contract.ErrorCode
import io.nook.contract.StructuredErrorException
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.ProjectItemTable
import io.nook.core.db.ProjectTable
import io.nook.core.db.ReleaseTable
import java.time.Instant
import kotlin.uuid.Uuid
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Reference resolution against seeded rows: UUID-form references resolve as
 * ids, all others as slugs, both are scoped to the target project — an entity
 * in another project is not found, whichever form names it — and only live rows
 * resolve at all.
 *
 * The deleted rows here are marked directly, because this is the layer beneath
 * the operation that marks them: what the write path does with the mark is its
 * own tests' subject.
 */
class ReferenceResolutionTest {

    private companion object {
        val db by lazy { Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase()) }
        val projectOne: Uuid = Uuid.random()
        val projectTwo: Uuid = Uuid.random()
        val deletedProject: Uuid = Uuid.random()
        val itemInOne: Uuid = Uuid.random()
        val itemInTwo: Uuid = Uuid.random()
        val deletedItem: Uuid = Uuid.random()
        val releaseInOne: Uuid = Uuid.random()
        val deletedRelease: Uuid = Uuid.random()

        val seeded by lazy {
            transaction(db) {
                ProjectTable.insert {
                    it[id] = projectOne
                    it[slug] = "project-one"
                    it[name] = "Project one"
                }
                ProjectTable.insert {
                    it[id] = projectTwo
                    it[slug] = "project-two"
                    it[name] = "Project two"
                }
                ProjectTable.insert {
                    it[id] = deletedProject
                    it[slug] = "gone-project"
                    it[name] = "Gone project"
                    it[deletedAt] = Instant.now()
                }
                ProjectItemTable.insert {
                    it[id] = itemInOne
                    it[projectId] = projectOne
                    it[type] = 2
                    it[slug] = "add-search"
                    it[name] = "Add search"
                }
                ProjectItemTable.insert {
                    it[id] = itemInTwo
                    it[projectId] = projectTwo
                    it[type] = 2
                    it[slug] = "other-item"
                    it[name] = "Other item"
                }
                ProjectItemTable.insert {
                    it[id] = deletedItem
                    it[projectId] = projectOne
                    it[type] = 2
                    it[slug] = "gone-item"
                    it[name] = "Gone item"
                    it[deletedAt] = Instant.now()
                }
                ReleaseTable.insert {
                    it[id] = releaseInOne
                    it[projectId] = projectOne
                    it[slug] = "v1"
                    it[name] = "v1"
                }
                ReleaseTable.insert {
                    it[id] = deletedRelease
                    it[projectId] = projectOne
                    it[slug] = "gone-release"
                    it[name] = "Gone release"
                    it[deletedAt] = Instant.now()
                }
            }
            true
        }
    }

    @BeforeTest
    fun seedOnce() {
        seeded
    }

    @Test
    fun `uuid shape detection matches canonical renderings only`() {
        assertEquals(true, isUuidShaped("3f2a8c1e-4b6d-4b0a-9f3e-2d1c0b9a8f7e"))
        assertEquals(true, isUuidShaped("3F2A8C1E-4B6D-4B0A-9F3E-2D1C0B9A8F7E"))
        assertEquals(false, isUuidShaped("3f2a"))
        assertEquals(false, isUuidShaped("add-search"))
        assertEquals(false, isUuidShaped("3f2a8c1e-4b6d-4b0a-9f3e"))
    }

    @Test
    fun `a project resolves by slug and by id`() {
        transaction(db) {
            assertEquals(projectOne, resolveProject("project-one")[ProjectTable.id])
            assertEquals(projectOne, resolveProject(projectOne.toString())[ProjectTable.id])
        }
    }

    @Test
    fun `an item resolves by slug and by id within its project`() {
        transaction(db) {
            assertEquals(itemInOne, resolveItem(projectOne, "add-search")[ProjectItemTable.id])
            assertEquals(itemInOne, resolveItem(projectOne, itemInOne.toString())[ProjectItemTable.id])
        }
    }

    @Test
    fun `a release resolves by slug and by id within its project`() {
        transaction(db) {
            assertEquals(releaseInOne, resolveRelease(projectOne, "v1")[ReleaseTable.id])
            assertEquals(releaseInOne, resolveRelease(projectOne, releaseInOne.toString())[ReleaseTable.id])
        }
    }

    @Test
    fun `an unknown reference is not found`() {
        transaction(db) {
            val bySlug = assertFailsWith<StructuredErrorException> { resolveItem(projectOne, "no-such-item") }
            assertEquals(ErrorCode.NOT_FOUND, bySlug.error.code)
            val byId = assertFailsWith<StructuredErrorException> {
                resolveItem(projectOne, Uuid.random().toString())
            }
            assertEquals(ErrorCode.NOT_FOUND, byId.error.code)
            val project = assertFailsWith<StructuredErrorException> { resolveProject("no-such-project") }
            assertEquals(ErrorCode.NOT_FOUND, project.error.code)
        }
    }

    @Test
    fun `an entity in another project is not found by slug or by id`() {
        transaction(db) {
            val bySlug = assertFailsWith<StructuredErrorException> { resolveItem(projectOne, "other-item") }
            assertEquals(ErrorCode.NOT_FOUND, bySlug.error.code)
            val byId = assertFailsWith<StructuredErrorException> {
                resolveItem(projectOne, itemInTwo.toString())
            }
            assertEquals(ErrorCode.NOT_FOUND, byId.error.code)
        }
    }

    @Test
    fun `a deleted row is not found under either form of reference`() {
        transaction(db) {
            mapOf<String, () -> Unit>(
                "a deleted project by slug" to { resolveProject("gone-project") },
                "a deleted project by id" to { resolveProject(deletedProject.toString()) },
                "a deleted item by slug" to { resolveItem(projectOne, "gone-item") },
                "a deleted item by id" to { resolveItem(projectOne, deletedItem.toString()) },
                "a deleted release by slug" to { resolveRelease(projectOne, "gone-release") },
                "a deleted release by id" to { resolveRelease(projectOne, deletedRelease.toString()) },
            ).forEach { (description, attempt) ->
                val failure = assertFailsWith<StructuredErrorException>(description) { attempt() }
                assertEquals(ErrorCode.NOT_FOUND, failure.error.code, description)
            }
        }
    }
}
