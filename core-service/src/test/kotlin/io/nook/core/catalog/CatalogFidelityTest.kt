package io.nook.core.catalog

import io.nook.contract.CatalogReply
import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.FieldChange
import io.nook.contract.ItemFilter
import io.nook.contract.ItemStatus
import io.nook.contract.ItemType
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateItem
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.ProjectItemTable
import io.nook.core.write.readItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * What the connection may not change about a call: the values the core is
 * invoked with, the two deletes answering nothing, and a listing arriving whole
 * and in order however large it is.
 *
 * The first of those is the one that cannot be seen from either end. A caller
 * gets back what the core produced from whatever the core was given, so a value
 * quietly trimmed or reordered on the way in would still produce a plausible
 * answer. What settles it is watching the core's own operation being invoked.
 */
class CatalogFidelityTest {

    private companion object {
        val db by lazy { Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase()) }
        val inProcess by lazy { CoreCatalog(db) }
    }

    @Test
    fun `the values the core is invoked with are the values that were handed over`() {
        val invokedWith = mutableListOf<List<Any?>>()
        val watched = InterceptingCatalog(inProcess) { _, arguments -> invokedWith += arguments }

        Connection(watched).use { connection ->
            val caller = connection.caller
            val project = caller.createProject(CreateProject("Faithful Crossing"))
            caller.createItem(project.slug, CreateItem(type = "task", name = "The blocker"))

            val awkward = CreateItem(
                type = "task",
                name = "Søk 🔍 épico",
                description = "Two lines,\nand \"quotation marks\"",
            )
            val created = caller.createItem(project.slug, awkward)
            assertEquals(listOf(project.slug, awkward), invokedWith.last())
            assertEquals(awkward.name, readItem(db, project.slug, created.slug).name)
            assertEquals(awkward.description, readItem(db, project.slug, created.slug).description)

            // The same reference twice: the connection deduplicates nothing, and
            // what collapses the pair is the core's own set, one layer further in.
            val twiceOver = UpdateItem(blockedBy = FieldChange.Set(listOf("the-blocker", "the-blocker")))
            caller.updateItem(project.slug, created.slug, twiceOver)
            assertEquals(listOf(project.slug, created.slug, twiceOver), invokedWith.last())

            // Nearly an id, and not one: a character short of the shape. What it
            // means is the core's to decide, so it has to arrive unchanged.
            val nearlyAnId = Uuid.random().toString().dropLast(1)
            assertFailsWith<StructuredErrorException> { caller.getItem(project.slug, nearlyAnId) }
            assertEquals(listOf(project.slug, nearlyAnId), invokedWith.last())
        }
    }

    @Test
    fun `both deletes report success, hand back no entity, and stay apart from a refusal`() {
        connectionTo(db).use { connection ->
            val project = connection.caller.createProject(CreateProject("Deleted Across"))
            connection.caller.createItem(project.slug, CreateItem(type = "task", name = "Doomed"))

            val deleteItem = """{"operation":"delete_item","project":"${project.slug}","payload":{"ref":"doomed"}}"""
            val deleteProject = """{"operation":"delete_project","payload":{"ref":"${project.slug}"}}"""

            // An answer carrying nothing at all, which is what "no entity" is on
            // the wire — and it still names its ending, so a caller reads success
            // from the reply rather than from the absence of a result.
            assertEquals(CatalogReply.Answer(), rawReply(connection.address, deleteItem))
            assertEquals(CatalogReply.Answer(), rawReply(connection.address, deleteProject))

            // Doing either again is a refusal, which is how a caller can tell the
            // two apart: nothing about success looks like being turned down.
            assertIs<CatalogReply.Refusal>(rawReply(connection.address, deleteProject))
            assertFailsWith<StructuredErrorException> { connection.caller.deleteProject(project.slug) }
        }
    }

    @Test
    fun `a listing of five thousand items arrives whole, in the core's order, inside the limit`() {
        val project = inProcess.createProject(CreateProject("Five Thousand"))
        // Seeded straight into the store: what is under test is the crossing, not
        // how the items got there, and five thousand creates would each open a
        // transaction and take the project's lock.
        transaction(db) {
            ProjectItemTable.batchInsert(1..5_000) { number ->
                this[ProjectItemTable.id] = Uuid.random()
                this[ProjectItemTable.projectId] = project.id
                this[ProjectItemTable.type] = ItemType.TASK.code
                this[ProjectItemTable.slug] = "seeded-$number"
                this[ProjectItemTable.name] = "Seeded $number"
                this[ProjectItemTable.status] = ItemStatus.TODO.code
            }
        }

        connectionTo(db).use { connection ->
            val (listed, took) = measureTimedValue {
                connection.caller.listItems(project.slug, ItemFilter())
            }

            assertEquals(5_000, listed.size)
            assertEquals(inProcess.listItems(project.slug, ItemFilter()), listed)
            assertTrue(took < 30.seconds, "the listing took $took, past the wait limit")
        }
    }
}
