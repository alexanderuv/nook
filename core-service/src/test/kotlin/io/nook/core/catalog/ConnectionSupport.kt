package io.nook.core.catalog

import io.nook.contract.CatalogClient
import io.nook.contract.CatalogOperation
import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.CreateRelease
import io.nook.contract.DEFAULT_WAIT_LIMIT
import io.nook.contract.ItemFilter
import io.nook.contract.OperationCatalog
import io.nook.contract.Project
import io.nook.contract.ProjectItem
import io.nook.contract.Release
import io.nook.contract.RpcReply
import io.nook.contract.RpcReplySerializer
import io.nook.contract.UpdateItem
import io.nook.contract.UpdateRelease
import io.nook.contract.catalogJson
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * The connection as an adapter meets it, assembled in the test process: a core
 * listening on a port the machine picks, and a caller pointed at it.
 *
 * Every misbehaviour the connection has to survive is arranged here rather than
 * in the core — a catalog that throws, one that sleeps, one that counts — so
 * the production path never learns it can misbehave.
 */

internal class Connection(
    catalog: OperationCatalog,
    waitLimit: Duration = DEFAULT_WAIT_LIMIT,
) : AutoCloseable {

    private val server = CatalogServer(catalog, LOOPBACK, port = 0)

    val address: String = server.start()

    val caller: CatalogClient = CatalogClient(address, waitLimit)

    override fun close() {
        caller.close()
        server.close()
    }
}

internal fun connectionTo(db: Database, waitLimit: Duration = DEFAULT_WAIT_LIMIT): Connection =
    Connection(CoreCatalog(db), waitLimit)

/**
 * A port nothing is listening on — for the checks that stop and restart the
 * core at one address, where letting the machine choose each time would point
 * the caller somewhere new.
 */
internal fun freePort(): Int = ServerSocket(0).use { it.localPort }

/**
 * A catalog wrapping another, with [before] run at the start of every call and
 * handed the operation and the values it was invoked with.
 *
 * One class for every misbehaving or watchful core the criteria ask for,
 * because they differ only in what that lambda does: throw, sleep, count, or
 * write down what arrived.
 */
internal class InterceptingCatalog(
    private val inner: OperationCatalog,
    private val before: (CatalogOperation, List<Any?>) -> Unit,
) : OperationCatalog {

    override fun createProject(command: CreateProject): Project {
        before(CatalogOperation.CREATE_PROJECT, listOf(command))
        return inner.createProject(command)
    }

    override fun getProject(ref: String): Project {
        before(CatalogOperation.GET_PROJECT, listOf(ref))
        return inner.getProject(ref)
    }

    override fun listProjects(): List<Project> {
        before(CatalogOperation.LIST_PROJECTS, emptyList())
        return inner.listProjects()
    }

    override fun deleteProject(ref: String) {
        before(CatalogOperation.DELETE_PROJECT, listOf(ref))
        inner.deleteProject(ref)
    }

    override fun createItem(projectRef: String, command: CreateItem): ProjectItem {
        before(CatalogOperation.CREATE_ITEM, listOf(projectRef, command))
        return inner.createItem(projectRef, command)
    }

    override fun updateItem(projectRef: String, itemRef: String, command: UpdateItem): ProjectItem {
        before(CatalogOperation.UPDATE_ITEM, listOf(projectRef, itemRef, command))
        return inner.updateItem(projectRef, itemRef, command)
    }

    override fun deleteItem(projectRef: String, itemRef: String) {
        before(CatalogOperation.DELETE_ITEM, listOf(projectRef, itemRef))
        inner.deleteItem(projectRef, itemRef)
    }

    override fun createRelease(projectRef: String, command: CreateRelease): Release {
        before(CatalogOperation.CREATE_RELEASE, listOf(projectRef, command))
        return inner.createRelease(projectRef, command)
    }

    override fun updateRelease(projectRef: String, releaseRef: String, command: UpdateRelease): Release {
        before(CatalogOperation.UPDATE_RELEASE, listOf(projectRef, releaseRef, command))
        return inner.updateRelease(projectRef, releaseRef, command)
    }

    override fun getItem(projectRef: String, itemRef: String): ProjectItem {
        before(CatalogOperation.GET_ITEM, listOf(projectRef, itemRef))
        return inner.getItem(projectRef, itemRef)
    }

    override fun listItems(projectRef: String, filter: ItemFilter): List<ProjectItem> {
        before(CatalogOperation.LIST_ITEMS, listOf(projectRef, filter))
        return inner.listItems(projectRef, filter)
    }
}

/**
 * Sends [body] to [address] exactly as written and reads the reply.
 *
 * The calling library cannot produce a malformed request — that is what it is
 * for — so the requests a defective adapter would send are written out by hand
 * here and posted by the runtime's own web client.
 */
internal fun rawReply(address: String, body: String): RpcReply =
    catalogJson.decodeFromString(RpcReplySerializer, rawExchange(address, body).body())

/**
 * One call, written out as text the way a program that is not this one writes
 * it: the operation by name, the project alongside its arguments, and an id for
 * the reply to hand back.
 */
internal fun rawCall(method: String, params: String = "{}", id: String = "1"): String =
    """{"jsonrpc":"2.0","method":"$method","params":$params,"id":$id}"""

/** The id [rawCall] uses unless it is told another, as it comes back on the reply. */
internal val ONE_CALL: JsonElement = JsonPrimitive(1)

internal fun rawExchange(address: String, body: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI.create(address))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    return java.net.http.HttpClient.newHttpClient()
        .send(request, HttpResponse.BodyHandlers.ofString())
}
