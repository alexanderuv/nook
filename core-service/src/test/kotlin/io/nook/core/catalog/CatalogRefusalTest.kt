package io.nook.core.catalog

import io.nook.contract.BreakdownException
import io.nook.contract.BreakdownOrigin
import io.nook.contract.CatalogReply
import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.ErrorCode
import io.nook.contract.FieldChange
import io.nook.contract.OperationCatalog
import io.nook.contract.StructuredError
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateItem
import io.nook.contract.catalogJson
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.allStructureTables
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * How a call ends, across the connection: the core's own refusals arriving
 * unchanged, a fault arriving as something a caller can never mistake for one,
 * and the requests this connection cannot read being refused before anything
 * reaches the store.
 *
 * The refusals are compared against the same call made inside the core's own
 * process rather than against expected text, because what is being checked is
 * that crossing changed nothing — and a literal would have to be edited into
 * agreement with any change, which is the opposite of the point.
 */
class CatalogRefusalTest {

    private companion object {
        val db by lazy { Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase()) }
        val inProcess by lazy { CoreCatalog(db) }
    }

    /** Every stored row of every structure table, rendered so any change shows. */
    private fun snapshot(): List<String> = transaction(db) {
        allStructureTables.flatMap { table ->
            table.selectAll().map { row ->
                table.tableName + table.columns.joinToString(prefix = "{", postfix = "}") {
                    "${it.name}=${row[it]}"
                }
            }
        }.sorted()
    }

    @Test
    fun `each of the four refusals arrives carrying the core's own code and message`() {
        val project = inProcess.createProject(CreateProject("Four Refusals"))
        inProcess.createItem(project.slug, CreateItem(type = "task", name = "Add search"))
        inProcess.createItem(project.slug, CreateItem(type = "task", name = "Index docs"))
        inProcess.updateItem(
            project.slug,
            "index-docs",
            UpdateItem(blockedBy = FieldChange.Set(listOf("add-search"))),
        )

        val refusals = listOf<(OperationCatalog) -> Unit>(
            { it.createItem(project.slug, CreateItem(type = "story", name = "Not a thing")) },
            { it.getItem(project.slug, "no-such-item") },
            { it.createProject(CreateProject("Taken", slug = project.slug)) },
            {
                it.updateItem(
                    project.slug,
                    "add-search",
                    UpdateItem(blockedBy = FieldChange.Set(listOf("index-docs"))),
                )
            },
        )

        connectionTo(db).use { connection ->
            val codes = refusals.map { attempt ->
                val here = assertFailsWith<StructuredErrorException> { attempt(inProcess) }.error
                val across = assertFailsWith<StructuredErrorException> { attempt(connection.caller) }.error
                assertEquals(here, across)
                across.code
            }
            assertEquals(ErrorCode.entries.toSet(), codes.toSet(), "the four refusals were not all driven")
        }
    }

    @Test
    fun `a refusal's details cross unchanged`() {
        // Details are part of what a refusal carries and have to survive. The
        // ones the operations fill in today say which of the things a call named
        // could not be found; a core carrying something else entirely is put in
        // the way here, so that what is checked is the crossing rather than one
        // operation's habits.
        val detailed = StructuredError(
            ErrorCode.CONFLICT,
            "two items already hold that handle",
            mapOf("slug" to "add-search", "holders" to "2"),
        )
        val refusing = InterceptingCatalog(inProcess) { _, _ -> throw StructuredErrorException(detailed) }
        Connection(refusing).use { connection ->
            assertEquals(
                detailed,
                assertFailsWith<StructuredErrorException> { connection.caller.listProjects() }.error,
            )
        }
    }

    @Test
    fun `a fault planted inside the core arrives as a breakdown carrying no refusal code`() {
        val broken = InterceptingCatalog(inProcess) { _, _ -> error("a defect planted inside the core") }
        Connection(broken).use { connection ->
            // The reply itself carries no code, because the shape a fault takes
            // has nowhere to put one — which is what keeps a caller from being
            // told to fix a request that was never the problem.
            assertIs<CatalogReply.Fault>(rawReply(connection.address, """{"operation":"list_projects"}"""))

            val breakdown = assertFailsWith<BreakdownException> { connection.caller.listProjects() }
            assertEquals(BreakdownOrigin.CORE, breakdown.origin)
        }
    }

    @Test
    fun `a request this connection cannot read is refused, and nothing reaches the store`() {
        val project = inProcess.createProject(CreateProject("Unreadable Requests"))
        val before = snapshot()

        connectionTo(db).use { connection ->
            mapOf(
                "an operation nobody defined" to
                    """{"operation":"teleport_item","project":"${project.slug}"}""",
                "contents that cannot be read at all" to
                    """this is not JSON""",
                "no contents at all" to
                    "",
                "a project-scoped call naming no project" to
                    """{"operation":"create_item","payload":{"type":"task","name":"Nowhere"}}""",
                "an instance-level call naming a project" to
                    """{"operation":"list_projects","project":"${project.slug}"}""",
                "a field the operation does not define" to
                    """{"operation":"create_item","project":"${project.slug}",""" +
                    """"payload":{"type":"task","name":"Extra","colour":"red"}}""",
                "a field the request itself does not define" to
                    """{"operation":"list_projects","hurry":true}""",
            ).forEach { (what, body) ->
                val exchange = rawExchange(connection.address, body)
                assertEquals(200, exchange.statusCode(), "$what: every reply carries the same status")
                val refusal = assertIs<CatalogReply.Refusal>(
                    catalogJson.decodeFromString<CatalogReply>(exchange.body()),
                    what,
                )
                assertEquals(ErrorCode.VALIDATION_FAILED, refusal.error.code, what)
            }

            assertEquals(before, snapshot(), "a request that could not be read reached the store")
            assertEquals(project, connection.caller.getProject(project.slug))
        }
    }
}
