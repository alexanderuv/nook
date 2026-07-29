package io.nook.core.catalog

import io.nook.contract.BreakdownException
import io.nook.contract.BreakdownOrigin
import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.ErrorCode
import io.nook.contract.FieldChange
import io.nook.contract.OperationCatalog
import io.nook.contract.RpcCode
import io.nook.contract.RpcReply
import io.nook.contract.RpcReplySerializer
import io.nook.contract.StructuredError
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateItem
import io.nook.contract.asStructuredError
import io.nook.contract.catalogJson
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.allStructureTables
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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
        val inProcess by lazy { CoreCatalog(db).forActor(CatalogBehavior.SOMEBODY) }
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
    fun `a defect planted inside the core arrives carrying no reason and naming no part of Nook`() {
        val broken = InterceptingCatalog(inProcess) { _, _ -> error("a defect planted inside the core") }
        Connection(broken).use { connection ->
            val reply = assertIs<RpcReply.Failed>(rawReply(connection.address, rawCall("list_projects")))
            assertEquals(RpcCode.INTERNAL_ERROR, reply.error.code)
            // Nothing for a caller to fix, so it must not read as though there
            // were: no domain failure named, and nothing said about which part
            // of Nook gave way.
            assertNull(reply.error.data, "a call that produced no verdict carried a reason")
            assertNull(reply.error.asStructuredError(), "a call that produced no verdict read back as a refusal")
            listOf("core", "connection", "database", "store", "catalog", "defect").forEach {
                assertFalse(reply.error.message.contains(it), "the reply named a part of Nook: ${reply.error.message}")
            }

            val breakdown = assertFailsWith<BreakdownException> { connection.caller.listProjects() }
            assertEquals(BreakdownOrigin.CORE, breakdown.origin)
        }
    }

    @Test
    fun `a request this connection cannot read is refused, and nothing reaches the store`() {
        val project = inProcess.createProject(CreateProject("Unreadable Requests"))
        val before = snapshot()

        connectionTo(db).use { connection ->
            // Each mistake against the number the wire's own table gives it: the
            // four the standard reserves for the request itself, and its
            // "invalid params" for everything about the arguments, whoever
            // found it wrong.
            mapOf(
                "contents that cannot be read at all" to
                    (RpcCode.PARSE_ERROR to "this is not JSON"),
                "no contents at all" to
                    (RpcCode.PARSE_ERROR to ""),
                "an envelope that is not a request" to
                    (RpcCode.INVALID_REQUEST to """{"method":"list_projects","id":1}"""),
                "a request carrying no id" to
                    (RpcCode.INVALID_REQUEST to """{"jsonrpc":"2.0","method":"list_projects"}"""),
                "a field the envelope itself does not define" to
                    (RpcCode.INVALID_REQUEST to """{"jsonrpc":"2.0","method":"list_projects","id":1,"hurry":true}"""),
                "an operation nobody defined" to
                    (RpcCode.METHOD_NOT_FOUND to rawCall("teleport_item", """{"project":"${project.slug}"}""")),
                "a project-scoped call naming no project" to
                    (RpcCode.INVALID_PARAMS to rawCall("create_item", """{"type":"task","name":"Nowhere"}""")),
                "an instance-level call naming a project" to
                    (RpcCode.INVALID_PARAMS to rawCall("list_projects", """{"project":"${project.slug}"}""")),
                "a field the operation does not define" to
                    (
                        RpcCode.INVALID_PARAMS to rawCall(
                            "create_item",
                            """{"project":"${project.slug}","type":"task","name":"Extra","colour":"red"}""",
                        )
                        ),
            ).forEach { (what, expected) ->
                val (code, body) = expected
                val exchange = rawExchange(connection.address, body)
                assertEquals(200, exchange.statusCode(), "$what: every reply carries the same status")
                val refused = assertIs<RpcReply.Failed>(
                    catalogJson.decodeFromString(RpcReplySerializer, exchange.body()),
                    what,
                )
                assertEquals(code, refused.error.code, what)
            }

            assertEquals(before, snapshot(), "a request that could not be read reached the store")
            assertEquals(project, connection.caller.getProject(project.slug))
        }
    }
}
