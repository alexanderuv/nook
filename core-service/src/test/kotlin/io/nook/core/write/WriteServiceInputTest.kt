package io.nook.core.write

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.CreateRelease
import io.nook.contract.ErrorCode
import io.nook.contract.FieldChange
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateItem
import io.nook.core.db.DocumentTable
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.ProjectItemTable
import io.nook.core.db.ProjectTable
import io.nook.core.db.ReleaseTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Ordinary caller mistakes about the size and content of text, all of which the
 * store would otherwise refuse in a form no caller can act on.
 *
 * Two escape routes, and they behave differently, which is why both are here. A
 * value wider than its column is rejected by the driver before a statement is
 * sent, so it never becomes a database error the write path could translate — it
 * arrives as a raw IllegalArgumentException from inside a library. A NUL is
 * rejected by PostgreSQL mid-statement, which the write path does catch, but
 * only to report as a fault in the service. Either way a caller asking for
 * something slightly too long would be told the service is broken.
 */
class WriteServiceInputTest {

    private companion object {
        val db by lazy { Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase()) }
        val service by lazy { WriteService(db) }

        /** A NUL, built from its code point: the character itself is invisible in source. */
        val NUL = Char(0)
    }

    private fun assertFailsWithCode(code: ErrorCode, block: () -> Unit): StructuredErrorException {
        val failure = assertFailsWith<StructuredErrorException> { block() }
        assertEquals(code, failure.error.code)
        return failure
    }

    /**
     * The limits the write path enforces are the widths the schema actually has.
     * Read from the declarations rather than repeated here: two numbers that
     * must agree and are written down twice will disagree eventually, and the
     * failure would be a caller error reported as an internal fault.
     */
    @Test
    fun `the enforced limits are the column widths of everything the write path names`() {
        assertEquals(setOf(MAX_NAME_LENGTH), widthsOf(written, "name"))
        assertEquals(setOf(MAX_SLUG_LENGTH), widthsOf(written, "slug"))
    }

    /**
     * The one name column these limits do not cover, recorded rather than
     * rounded off. A document's name is narrower than every other, and no
     * operation writes one yet — so when documents get a write path, [
     * MAX_NAME_LENGTH] is the wrong bound for them and this is the test that
     * says so out loud.
     */
    @Test
    fun `a document name is narrower than the rest and will need a bound of its own`() {
        val documentName = widthsOf(arrayOf(DocumentTable), "name").single()

        assertTrue(
            documentName < MAX_NAME_LENGTH,
            "document.name is $documentName, which the write path's limit of $MAX_NAME_LENGTH now covers; " +
                "if the widths have been made equal, fold this back into the test above",
        )
    }

    /** Every table whose rows the write path creates, and therefore names. */
    private val written = arrayOf(ProjectTable, ProjectItemTable, ReleaseTable)

    private fun widthsOf(tables: Array<out Table>, columnName: String): Set<Int> =
        tables.flatMap { table -> table.columns }
            .filter { it.name == columnName }
            .mapNotNull { (it.columnType as? VarCharColumnType)?.colLength }
            .toSet()

    @Test
    fun `a name wider than its column is a caller error, not a fault in the service`() {
        val project = service.createProject(CreateProject("Long Names"))
        val tooLong = "a".repeat(MAX_NAME_LENGTH + 1)

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) { service.createProject(CreateProject(tooLong)) }
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.createItem(project.slug, CreateItem(type = "task", name = tooLong))
        }
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.createRelease(project.slug, CreateRelease(tooLong))
        }

        service.createItem(project.slug, CreateItem(type = "task", name = "Renameable"))
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(project.slug, "renameable", UpdateItem(name = FieldChange.Set(tooLong)))
        }

        // The boundary itself is accepted, so the rule is a limit and not an
        // accidental margin below one.
        val atTheLimit = service.createItem(
            project.slug,
            CreateItem(type = "task", name = "b".repeat(MAX_NAME_LENGTH), slug = "at-the-limit"),
        )
        assertEquals(MAX_NAME_LENGTH, atTheLimit.name.length)
    }

    @Test
    fun `an explicit slug wider than its column is a caller error`() {
        val project = service.createProject(CreateProject("Long Slugs"))

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.createItem(
                project.slug,
                CreateItem(type = "task", name = "Fine name", slug = "c".repeat(MAX_SLUG_LENGTH + 1)),
            )
        }
        val atTheLimit = service.createItem(
            project.slug,
            CreateItem(type = "task", name = "Also fine", slug = "d".repeat(MAX_SLUG_LENGTH)),
        )
        assertEquals(MAX_SLUG_LENGTH, atTheLimit.slug.length)
    }

    @Test
    fun `a NUL anywhere in caller text is a caller error`() {
        val project = service.createProject(CreateProject("Nul Bytes"))

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.createProject(CreateProject("Before${NUL}after"))
        }
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.createItem(
                project.slug,
                CreateItem(type = "task", name = "Fine", description = "Before${NUL}after"),
            )
        }
        service.createItem(project.slug, CreateItem(type = "task", name = "Describable"))
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(
                project.slug, "describable",
                UpdateItem(description = FieldChange.Set("Before${NUL}after")),
            )
        }
    }

    /**
     * A name can be more than twice as wide as a slug, so a slug derived from
     * one has to be cut — including when a numeric suffix is added, which is
     * where the room to grow runs out.
     */
    @Test
    fun `a slug derived from a name at full width fits its column, suffix and all`() {
        val project = service.createProject(CreateProject("Derived Widths"))
        val wideName = "e".repeat(MAX_NAME_LENGTH)

        val slugs = (1..3).map {
            service.createItem(project.slug, CreateItem(type = "task", name = wideName)).slug
        }

        slugs.forEach { slug ->
            assertTrue(slug.length <= MAX_SLUG_LENGTH, "derived slug is ${slug.length} characters: $slug")
        }
        assertEquals(3, slugs.toSet().size, "each derivation must land on a free handle: $slugs")
        assertEquals(slugs[0], readItem(db, project.slug, slugs[0]).slug, "and each must resolve")
    }

    /**
     * The rule an explicit slug already had, applied where the slug is derived.
     * A slug in UUID form can never be used: a reference in that form resolves
     * as an id, so the entity would be unreachable by the only handle it has.
     */
    @Test
    fun `a name that derives a UUID-shaped slug is refused rather than made unreachable`() {
        val uuidShapedName = "3F2A8C1E 4B6D 4B0A 9F3E 2D1C0B9A8F7E"

        val failure = assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.createProject(CreateProject(uuidShapedName))
        }
        assertTrue(
            failure.error.message.contains("UUID"),
            "the message must say why: ${failure.error.message}",
        )

        val project = service.createProject(CreateProject("Uuid Shaped Items"))
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.createItem(project.slug, CreateItem(type = "task", name = uuidShapedName))
        }
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.createRelease(project.slug, CreateRelease(uuidShapedName))
        }

        // Nothing was written under a handle nobody could name again.
        assertEquals(
            0L,
            org.jetbrains.exposed.v1.jdbc.transactions.transaction(db) {
                ProjectTable.selectAll().where {
                    ProjectTable.slug eq "3f2a8c1e-4b6d-4b0a-9f3e-2d1c0b9a8f7e"
                }.count() +
                    ProjectItemTable.selectAll().where {
                        ProjectItemTable.slug eq "3f2a8c1e-4b6d-4b0a-9f3e-2d1c0b9a8f7e"
                    }.count()
            },
        )
    }
}
