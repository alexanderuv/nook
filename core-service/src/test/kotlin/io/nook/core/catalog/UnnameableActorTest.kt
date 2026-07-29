package io.nook.core.catalog

import io.nook.contract.ALEX
import io.nook.contract.AN_AGENT
import io.nook.contract.Actor
import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.CreateRelease
import io.nook.contract.ErrorCode
import io.nook.contract.FieldChange
import io.nook.contract.ItemFilter
import io.nook.contract.JORDAN
import io.nook.contract.OperationCatalog
import io.nook.contract.RpcReply
import io.nook.contract.UpdateItem
import io.nook.contract.UpdateRelease
import io.nook.contract.asStructuredError
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.ItemDependencyTable
import io.nook.core.db.allStructureTables
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** The five fields no operation defines, under the names they carry on an entity. */
private val THE_FIVE = listOf("ownerSubject", "createdBy", "updatedBy", "createdByAgent", "updatedByAgent")

/**
 * What a caller cannot say about who its call is for, and what nothing records.
 *
 * Who a call is for is settled by the token it presented and by nothing a caller
 * writes — so each of the five fields sent as an argument is refused by the rule
 * that was already there for any field nobody defined, and a caller putting the
 * two crossing headers on its own request reaches an adapter that never reads them.
 *
 * The other half is what is deliberately *not* recorded: a blocker edge names
 * nobody by having no column to name one in, and a delete leaves no trace of who
 * removed what, the rows being gone.
 */
class UnnameableActorTest {

    private companion object {
        val db by lazy { Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase()) }
    }

    private val somebody = Actor(ALEX, AN_AGENT)

    private fun madeBy(actor: Actor): OperationCatalog = CoreCatalog(db).forActor(actor)

    /** Every stored row of every structure table, rendered so any change shows. */
    private fun snapshot(): List<String> = transaction(db) {
        allStructureTables.flatMap { table ->
            table.selectAll().map { row ->
                table.tableName + table.columns.joinToString(prefix = "{", postfix = "}") { "${it.name}=${row[it]}" }
            }
        }.sorted()
    }

    @Test
    fun `each of the five fields sent as an argument is refused as a field the operation does not define`() {
        val project = madeBy(somebody).createProject(CreateProject("Unnameable"))
        madeBy(somebody).createItem(project.slug, CreateItem(type = "task", name = "Add search"))

        connectionTo(db).use { connection ->
            val before = snapshot()
            THE_FIVE.forEach { field ->
                listOf(
                    rawCall("create_project", """{"name":"Smuggled","$field":"$JORDAN"}"""),
                    rawCall("create_item", """{"project":"${project.slug}","type":"task","name":"S","$field":"$JORDAN"}"""),
                    rawCall("update_item", """{"project":"${project.slug}","ref":"add-search","$field":"$JORDAN"}"""),
                    rawCall("create_release", """{"project":"${project.slug}","name":"v1","$field":"$JORDAN"}"""),
                ).forEach { body ->
                    val refused = assertIs<RpcReply.Failed>(rawReply(connection.address, body), "$field in $body")
                    val said = refused.error.asStructuredError()
                    assertEquals(ErrorCode.VALIDATION_FAILED, said?.code, "$field in $body")
                    assertTrue(
                        said?.message?.contains(field) == true,
                        "the refusal did not name $field; it said: ${said?.message}",
                    )
                }
            }
            assertEquals(before, snapshot(), "a call naming one of the five changed the store")
        }
    }

    @Test
    fun `a call that names somebody else in a header of its own records the person its adapter read`() {
        connectionTo(db).use { connection ->
            // What the adapter tells the core is one thing; what a caller writes
            // on its own request is another, and only the first reaches here —
            // the adapters take the person from the token and read no such header.
            // Sent straight at the core this *is* the adapter's word, which is the
            // arrangement's whole assumption: only this machine can reach it.
            val body = rawCall("create_project", """{"name":"Whose Word"}""")
            assertIs<RpcReply.Answered>(rawReply(connection.address, body, naming = Actor(ALEX, AN_AGENT)))
        }
        assertEquals(ALEX, madeBy(somebody).getProject("whose-word").createdBy)
    }

    @Test
    fun `a project's owner still reads what it read when it was created, whatever has run against it`() {
        val core = madeBy(somebody)
        val project = core.createProject(CreateProject("Owner Holds"))
        val owner = project.ownerSubject

        val byJordan = madeBy(Actor(JORDAN, AN_AGENT))
        byJordan.createRelease(project.slug, CreateRelease("v1"))
        byJordan.updateRelease(project.slug, "v1", UpdateRelease(name = FieldChange.Set("Autumn")))
        byJordan.createItem(project.slug, CreateItem(type = "epic", name = "Search core", releaseRef = "v1"))
        byJordan.createItem(project.slug, CreateItem(type = "task", name = "Blocker one"))
        byJordan.createItem(project.slug, CreateItem(type = "task", name = "Add search", parentRef = "search-core"))
        byJordan.updateItem(
            project.slug,
            "add-search",
            UpdateItem(blockedBy = FieldChange.Set(listOf("blocker-one"))),
        )
        byJordan.getProject(project.slug)
        byJordan.listProjects()
        byJordan.getItem(project.slug, "add-search")
        byJordan.listItems(project.slug, ItemFilter())
        byJordan.deleteItem(project.slug, "add-search")

        assertEquals(owner, byJordan.getProject(project.slug).ownerSubject, "the project changed hands")
        assertEquals(ALEX, byJordan.getProject(project.slug).ownerSubject)
    }

    @Test
    fun `replacing a blocker set advances the item, and the edges hold no such field at all`() {
        val core = madeBy(somebody)
        val project = core.createProject(CreateProject("Edges Record Nobody"))
        core.createItem(project.slug, CreateItem(type = "task", name = "Blocker one"))
        core.createItem(project.slug, CreateItem(type = "task", name = "Blocker two"))
        core.createItem(project.slug, CreateItem(type = "task", name = "Add search"))

        val replaced = madeBy(Actor(JORDAN, null)).updateItem(
            project.slug,
            "add-search",
            UpdateItem(blockedBy = FieldChange.Set(listOf("blocker-one", "blocker-two"))),
        )

        assertEquals(2, replaced.blockedBy.size, "the blockers were not replaced")
        assertEquals(JORDAN, replaced.updatedBy, "replacing an item's blockers did not advance the item")
        assertEquals("", replaced.updatedByAgent, "a person acting alone was recorded as an agent")

        // The edges themselves record nobody, and do so by having nowhere to
        // record one — which is a rule the schema keeps rather than the code.
        assertEquals(
            emptyList(),
            ItemDependencyTable.columns.map { it.name }.filter { it.contains("by") },
            "a blocker edge has a column naming who made it",
        )
    }

    @Test
    fun `deleting an epic takes its children and leaves no row anywhere naming who removed them`() {
        val core = madeBy(somebody)
        val project = core.createProject(CreateProject("Nothing Left Behind"))
        core.createItem(project.slug, CreateItem(type = "epic", name = "Search core"))
        core.createItem(project.slug, CreateItem(type = "task", name = "Add search", parentRef = "search-core"))
        core.createItem(project.slug, CreateItem(type = "task", name = "Rank results", parentRef = "search-core"))

        val before = snapshot()
        madeBy(Actor(JORDAN, AN_AGENT)).deleteItem(project.slug, "search-core")
        val after = snapshot()

        assertTrue(after.size < before.size, "the epic and its children are still there")
        assertTrue(
            after.none { it.contains("=$JORDAN") },
            "a row somewhere records who deleted the epic or its children",
        )
    }
}
