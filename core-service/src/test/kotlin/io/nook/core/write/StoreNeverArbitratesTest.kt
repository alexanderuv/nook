package io.nook.core.write

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.CreateRelease
import io.nook.contract.ErrorCode
import io.nook.contract.FieldChange
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateItem
import io.nook.contract.UpdateRelease
import io.nook.core.catalog.CatalogBehavior.Companion.SOMEBODY
import io.nook.core.catalog.CoreCatalog
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.ItemDependencyTable
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert

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
        val service by lazy { CoreCatalog(db).forActor(SOMEBODY) }
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
            service.updateItem(
                here.slug, "local-leaf",
                UpdateItem(blockedBy = FieldChange.Set(listOf(Uuid.random().toString()))),
            )
        }
    }

    @Test
    fun `an item blocking itself is refused before the store sees it`() {
        val project = service.createProject(CreateProject(name = "No Self Block"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Alone"))

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(project.slug, "alone", UpdateItem(blockedBy = FieldChange.Set(listOf("alone"))))
        }
    }

    @Test
    fun `the same blocker named twice collapses instead of colliding in the store`() {
        val project = service.createProject(CreateProject(name = "Repeated Blocker"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Blocker"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Blocked"))

        val blocked = service.updateItem(
            project.slug, "blocked",
            UpdateItem(blockedBy = FieldChange.Set(listOf("blocker", "blocker"))),
        )
        assertEquals(1, blocked.blockedBy.size)
    }

    /**
     * The other half of the claim: when the store *does* refuse — which can only
     * mean validation missed a case — the refusal arrives as a fault in this
     * service, carrying what the store said, and never dressed up as one of the
     * four codes a caller can act on.
     *
     * Reached by writing a row no operation would build: a dependency edge whose
     * two ends are the same item, which the schema's only CHECK constraint
     * refuses. The service already refuses this before the store sees it, so the
     * lock has to be taken the long way round to reach the constraint at all.
     */
    @Test
    fun `a refusal from the store travels as a fault in this service, not as a caller error`() {
        val project = service.createProject(CreateProject(name = "Store Refusal"))
        val item = service.createItem(project.slug, CreateItem(type = "task", name = "Alone again"))

        val failure = assertFailsWith<IllegalStateException> {
            writeTransaction(db) {
                ItemDependencyTable.insert {
                    it[ItemDependencyTable.itemId] = item.id
                    it[ItemDependencyTable.dependsOnId] = item.id
                }
            }
        }
        assertEquals(
            "the store refused a write that validation should have refused first",
            failure.message,
        )
        assertEquals(
            true,
            failure.cause?.message.orEmpty().contains("ck_dep_no_self_block"),
            "the store's own complaint must be attached, got: ${failure.cause?.message}",
        )
    }

    /**
     * A write runs once. Exposed re-runs a failed block up to three times by
     * default, which is a hazard for code that has already taken a lock and may
     * already have written something — and it is invisible, because a retried
     * block that eventually fails looks exactly like one that failed once.
     */
    @Test
    fun `a failing write is attempted once, never silently re-run`() {
        val attempts = AtomicInteger(0)

        assertFailsWith<IllegalStateException> {
            writeTransaction(db) {
                attempts.incrementAndGet()
                exec("SELECT * FROM a_table_that_does_not_exist")
            }
        }

        assertEquals(1, attempts.get(), "the block ran more than once")
    }
}
