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
import io.nook.contract.WRITTEN_IN_ANOTHER_SCRIPT
import io.nook.contract.asStructuredError
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.allStructureTables
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** What no agent acted looks like on a row: empty text, not a name nobody has. */
private const val NO_AGENT = ""

/**
 * What the core records about who wrote a row, against the real store.
 *
 * Two identities on every row, and the pair moves as a pair: what created a row
 * stays as it was for good, and what last changed it is replaced whole on every
 * change — including the agent, which is *cleared* where a person acted alone
 * rather than left saying an agent made a change it did not make.
 *
 * The mutations naming nobody are the other half. The store carries a fallback
 * for the rows that predate any of this, and it must stay unreachable through
 * the connection: an adapter with a defect gets a refusal rather than a row nobody
 * can attribute.
 */
class ActorRecordedTest {

    private companion object {
        val db by lazy { Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase()) }
    }

    private val anAgentFor = Actor(ALEX, AN_AGENT)

    private val alexAlone = Actor(ALEX, null)

    private val anAgentForJordan = Actor(JORDAN, AN_AGENT)

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
    fun `a created row names its person in both audit fields and its agent in both agent fields`() {
        val core = madeBy(anAgentFor)
        val project = core.createProject(CreateProject("Recorded Creates"))
        val item = core.createItem(project.slug, CreateItem(type = "task", name = "Add search"))
        val release = core.createRelease(project.slug, CreateRelease("v1"))

        listOf(
            "project" to listOf(project.createdBy, project.updatedBy, project.createdByAgent, project.updatedByAgent),
            "item" to listOf(item.createdBy, item.updatedBy, item.createdByAgent, item.updatedByAgent),
            "release" to listOf(release.createdBy, release.updatedBy, release.createdByAgent, release.updatedByAgent),
        ).forEach { (what, recorded) ->
            assertEquals(listOf(ALEX, ALEX, AN_AGENT, AN_AGENT), recorded, what)
        }
        assertEquals(ALEX, project.ownerSubject, "the project did not record who it is for")

        // And read back off the store rather than off what the write returned,
        // because those are two different journeys for the same five values.
        assertEquals(project, core.getProject(project.slug))
        assertEquals(item, core.getItem(project.slug, "add-search"))
    }

    @Test
    fun `a change replaces what last touched a row and leaves what created it alone`() {
        val core = madeBy(anAgentFor)
        val project = core.createProject(CreateProject("Recorded Changes"))
        core.createItem(project.slug, CreateItem(type = "task", name = "Add search"))
        core.createRelease(project.slug, CreateRelease("v1"))

        val byJordan = madeBy(anAgentForJordan)
        val item = byJordan.updateItem(project.slug, "add-search", UpdateItem(name = FieldChange.Set("Search")))
        val release = byJordan.updateRelease(project.slug, "v1", UpdateRelease(name = FieldChange.Set("Autumn")))

        assertEquals(listOf(ALEX, JORDAN), listOf(item.createdBy, item.updatedBy))
        assertEquals(listOf(ALEX, JORDAN), listOf(release.createdBy, release.updatedBy))
        assertEquals(listOf(AN_AGENT, AN_AGENT), listOf(item.createdByAgent, item.updatedByAgent))
    }

    @Test
    fun `a person changing a row an agent created leaves the created agent and clears the updated one`() {
        val project = madeBy(anAgentFor).createProject(CreateProject("Taken Over"))
        madeBy(anAgentFor).createItem(project.slug, CreateItem(type = "task", name = "Add search"))

        val changed = madeBy(alexAlone)
            .updateItem(project.slug, "add-search", UpdateItem(name = FieldChange.Set("Search, changed")))

        assertEquals(AN_AGENT, changed.createdByAgent, "the agent that created the row was overwritten")
        assertEquals(NO_AGENT, changed.updatedByAgent, "a person acting alone was recorded as an agent")
    }

    @Test
    fun `an agent changing a row a person created leaves the created agent empty`() {
        val project = madeBy(alexAlone).createProject(CreateProject("Handed Over"))
        madeBy(alexAlone).createItem(project.slug, CreateItem(type = "task", name = "Add search"))

        val changed = madeBy(anAgentForJordan)
            .updateItem(project.slug, "add-search", UpdateItem(name = FieldChange.Set("Search, changed")))

        assertEquals(NO_AGENT, changed.createdByAgent, "a person working directly was credited to an agent")
        assertEquals(AN_AGENT, changed.updatedByAgent, "the agent that made the change was not recorded")
    }

    @Test
    fun `a person written in emoji and another script is recorded exactly as the token held it`() {
        val core = madeBy(Actor(WRITTEN_IN_ANOTHER_SCRIPT, AN_AGENT))
        val project = core.createProject(CreateProject("Another Script"))

        assertEquals(WRITTEN_IN_ANOTHER_SCRIPT, project.createdBy)
        assertEquals(WRITTEN_IN_ANOTHER_SCRIPT, project.ownerSubject)
        assertEquals(
            WRITTEN_IN_ANOTHER_SCRIPT.toByteArray(Charsets.UTF_8).toList(),
            core.getProject(project.slug).createdBy.toByteArray(Charsets.UTF_8).toList(),
            "the person's name did not survive the store byte for byte",
        )
    }

    @Test
    fun `an update naming no field at all still succeeds`() {
        val project = madeBy(anAgentFor).createProject(CreateProject("Nothing To Change"))
        madeBy(anAgentFor).createItem(project.slug, CreateItem(type = "task", name = "Add search"))

        // Whether the two fields advance is nobody's business — no behavior may
        // depend on it, on the same terms the timestamp beside them already set.
        madeBy(alexAlone).updateItem(project.slug, "add-search", UpdateItem())
    }

    @Test
    fun `each of the seven mutations naming no person is refused, and writes nothing`() {
        val setUp = madeBy(anAgentFor)
        val project = setUp.createProject(CreateProject("Naming Nobody"))
        setUp.createItem(project.slug, CreateItem(type = "task", name = "Add search"))
        setUp.createRelease(project.slug, CreateRelease("v1"))

        connectionTo(db).use { connection ->
            val before = snapshot()
            val sevenMutations = mapOf(
                "create_project" to rawCall("create_project", """{"name":"By Nobody"}"""),
                "create_item" to rawCall(
                    "create_item",
                    """{"project":"${project.slug}","type":"task","name":"By Nobody"}""",
                ),
                "update_item" to rawCall(
                    "update_item",
                    """{"project":"${project.slug}","ref":"add-search","name":"Renamed"}""",
                ),
                "delete_item" to rawCall("delete_item", """{"project":"${project.slug}","ref":"add-search"}"""),
                "create_release" to rawCall("create_release", """{"project":"${project.slug}","name":"v2"}"""),
                "update_release" to rawCall(
                    "update_release",
                    """{"project":"${project.slug}","ref":"v1","name":"Autumn"}""",
                ),
                "delete_project" to rawCall("delete_project", """{"ref":"${project.slug}"}"""),
            )
            assertEquals(7, sevenMutations.size, "the seven mutations were not all driven")

            sevenMutations.forEach { (what, body) ->
                val refused = assertIs<RpcReply.Failed>(rawReply(connection.address, body, Actor.NOBODY), what)
                assertEquals(ErrorCode.VALIDATION_FAILED, refused.error.asStructuredError()?.code, what)
            }
            assertEquals(before, snapshot(), "a mutation naming nobody changed the store")
        }
    }

    @Test
    fun `each of the four reads naming no person is served, the core asking for no credential of its own`() {
        val setUp = madeBy(anAgentFor)
        val project = setUp.createProject(CreateProject("Read By Nobody"))
        setUp.createItem(project.slug, CreateItem(type = "task", name = "Add search"))

        connectionTo(db).use { connection ->
            val fourReads = mapOf(
                "get_project" to rawCall("get_project", """{"ref":"${project.slug}"}"""),
                "list_projects" to rawCall("list_projects"),
                "get_item" to rawCall("get_item", """{"project":"${project.slug}","ref":"add-search"}"""),
                "list_items" to rawCall("list_items", """{"project":"${project.slug}"}"""),
            )
            assertEquals(4, fourReads.size, "the four reads were not all driven")

            // No Authorization header goes with any of these, and none is asked
            // for: only the machine the core runs on can reach it, and that
            // stays the whole of its protection.
            fourReads.forEach { (what, body) ->
                assertIs<RpcReply.Answered>(rawReply(connection.address, body, Actor.NOBODY), what)
            }
        }
    }

    @Test
    fun `a read is served whoever it names, and hands back the five fields either way`() {
        val project = madeBy(anAgentFor).createProject(CreateProject("Read By Anybody"))
        madeBy(anAgentFor).createItem(project.slug, CreateItem(type = "task", name = "Add search"))

        assertEquals(
            madeBy(anAgentFor).listItems(project.slug, ItemFilter()),
            madeBy(anAgentForJordan).listItems(project.slug, ItemFilter()),
            "a listing answered differently depending on who asked",
        )
    }
}
