package io.nook.core.write

import io.nook.contract.ErrorCode
import io.nook.contract.StructuredErrorException
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.ProjectItemTable
import io.nook.core.db.ProjectTable
import io.nook.core.db.ReleaseTable
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
 * ids, all others as slugs, and both are scoped to the target project — an
 * entity in another project is not found, whichever form names it.
 */
class ReferenceResolutionTest {

    private companion object {
        val db by lazy { Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase()) }
        val projectOne: Uuid = Uuid.random()
        val projectTwo: Uuid = Uuid.random()
        val itemInOne: Uuid = Uuid.random()
        val itemInTwo: Uuid = Uuid.random()
        val releaseInOne: Uuid = Uuid.random()

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
                ReleaseTable.insert {
                    it[id] = releaseInOne
                    it[projectId] = projectOne
                    it[slug] = "v1"
                    it[name] = "v1"
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
}
