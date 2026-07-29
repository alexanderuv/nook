package io.nook.core.catalog

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.CreateRelease
import io.nook.contract.FieldChange
import io.nook.contract.ItemFilter
import io.nook.contract.UpdateItem
import io.nook.contract.catalogJson
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.read.ReadService
import io.nook.core.write.WriteService
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Entities the core has just produced, written out and read back, compared as
 * whole values.
 *
 * Whole values rather than named fields, and produced by the write path rather
 * than assembled here, for the same reason: a conversion that never learned
 * about a field compiles perfectly and drops it in silence. A check that names
 * the fields it looks at would be edited into agreement with the mistake, and
 * an entity built by hand would not carry the microsecond timestamps and real
 * identifiers the store hands out.
 */
class EntityCrossingTest {

    private companion object {
        val db by lazy { Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase()) }
        val writes by lazy { CoreCatalog(db).forActor(CatalogBehavior.Companion.SOMEBODY) }
        val reads by lazy { ReadService(db) }
    }

    private inline fun <reified T> crossed(value: T): T =
        catalogJson.decodeFromString(catalogJson.encodeToString(value))

    @Test
    fun `every entity the core produces comes back equal to itself`() {
        val project = writes.createProject(
            CreateProject("Søk 🔍", description = "Two lines,\nand \"quotation marks\""),
        )
        val release = writes.createRelease(
            project.slug,
            CreateRelease("v1", description = "Shipping in the autumn", targetDate = LocalDate.of(2026, 12, 24)),
        )
        val epic = writes.createItem(
            project.slug,
            CreateItem(type = "epic", name = "Search core", releaseRef = "v1"),
        )
        val firstBlocker = writes.createItem(project.slug, CreateItem(type = "task", name = "Index docs"))
        val secondBlocker = writes.createItem(project.slug, CreateItem(type = "task", name = "Rank results"))
        val named = writes.createItem(
            project.slug,
            CreateItem(type = "task", name = "Søk 🔍 épico", parentRef = "search-core"),
        )
        val blocked = writes.updateItem(
            project.slug,
            named.slug,
            UpdateItem(blockedBy = FieldChange.Set(listOf(firstBlocker.slug, secondBlocker.slug))),
        )

        assertEquals(project, crossed(project))
        assertEquals(release, crossed(release))
        assertEquals(epic, crossed(epic))
        assertEquals(blocked, crossed(blocked))
        assertEquals(setOf(firstBlocker.id, secondBlocker.id), crossed(blocked).blockedBy)

        val listing = reads.listItems(project.slug, ItemFilter())
        assertEquals(listing, crossed(listing))
    }

    @Test
    fun `a field the core left empty arrives empty, not as text of no length`() {
        val bare = writes.createProject(CreateProject("Bare"))
        val item = writes.createItem(bare.slug, CreateItem(type = "task", name = "Nothing else"))

        assertNull(crossed(bare).description)
        assertNull(crossed(bare).artifactRepoUrl)
        assertNull(crossed(item).description)
        assertNull(crossed(item).parentId)
        assertNull(crossed(item).releaseId)
        assertEquals(emptySet(), crossed(item).blockedBy)
    }
}
