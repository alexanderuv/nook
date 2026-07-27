package io.nook.contract

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject

/** How long a call waits for an answer before it becomes a breakdown. */
public val DEFAULT_WAIT_LIMIT: Duration = 30.seconds

/**
 * The one piece of code that calls the core and reads its replies, shared by
 * both adapters — which is what makes them unable to read the same reply
 * differently.
 *
 * It offers the same eleven operations under the same names, takes the same
 * commands and filter, and returns the same entities. A call ends in exactly
 * one of three ways: it answers, it throws [StructuredErrorException] carrying
 * the core's own refusal unchanged, or it throws [BreakdownException] — which
 * says whether the core broke or could not be reached.
 *
 * Nothing here sends a call a second time, and nothing is installed that would.
 * A write cannot be repeated safely, and no rule that repeats only some calls
 * is worth the risk of getting the set wrong; a caller that stopped waiting is
 * told about the wait, not about whether the work happened, and a later read is
 * what settles that.
 *
 * One web client serves every call and outlives them all, so a caller built
 * before the core is up recovers on its own once the core is there, without
 * being rebuilt. [close] releases it.
 *
 * [waitLimit] is taken here rather than fixed, because the connection has to be
 * shown to give up where it says it does *and* shown not to disturb a caller
 * that gives up mid-write — and no real operation takes half a minute to make
 * the first demonstrable.
 */
public class CatalogClient(
    private val address: String,
    waitLimit: Duration = DEFAULT_WAIT_LIMIT,
) : OperationCatalog, AutoCloseable {

    private val http = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = waitLimit.inWholeMilliseconds }
    }

    override fun createProject(command: CreateProject): Project =
        call(createProjectWiring, null, command)

    override fun getProject(ref: String): Project =
        call(getProjectWiring, null, TargetRef(ref))

    override fun listProjects(): List<Project> =
        call(listProjectsWiring, null, EmptyPayload)

    override fun deleteProject(ref: String): Unit =
        call(deleteProjectWiring, null, TargetRef(ref))

    override fun createItem(projectRef: String, command: CreateItem): ProjectItem =
        call(createItemWiring, projectRef, command)

    override fun updateItem(projectRef: String, itemRef: String, command: UpdateItem): ProjectItem =
        call(updateItemWiring, projectRef, ItemUpdate(itemRef, command))

    override fun deleteItem(projectRef: String, itemRef: String): Unit =
        call(deleteItemWiring, projectRef, TargetRef(itemRef))

    override fun createRelease(projectRef: String, command: CreateRelease): Release =
        call(createReleaseWiring, projectRef, command)

    override fun updateRelease(projectRef: String, releaseRef: String, command: UpdateRelease): Release =
        call(updateReleaseWiring, projectRef, ReleaseUpdate(releaseRef, command))

    override fun getItem(projectRef: String, itemRef: String): ProjectItem =
        call(getItemWiring, projectRef, TargetRef(itemRef))

    override fun listItems(projectRef: String, filter: ItemFilter): List<ProjectItem> =
        call(listItemsWiring, projectRef, filter)

    override fun close(): Unit = http.close()

    private fun <P, R> call(wiring: WiredOperation<P, R>, project: String?, payload: P): R {
        val request = CatalogRequest(
            operation = wiring.operation.label,
            project = project,
            payload = catalogJson.encodeToJsonElement(wiring.payloadShape, payload).jsonObject,
        )
        return when (val reply = exchange(catalogJson.encodeToString(request))) {
            is CatalogReply.Answer -> wiring.answerShape.read(catalogJson, reply.result)
            is CatalogReply.Refusal -> throw StructuredErrorException(reply.error)
            is CatalogReply.Fault -> throw BreakdownException(BreakdownOrigin.CORE, reply.message, null)
        }
    }

    /**
     * Sends one request and reads back one reply, or reports why there was
     * none. Everything that is not a reply is the same kind of thing here —
     * nothing listening, a link that dropped, a wait that ran out, an answer
     * that could not be read — so it is caught broadly on purpose: a caller
     * needs to know a verdict never arrived, and the cause it carries says the
     * rest.
     */
    private fun exchange(request: String): CatalogReply {
        val answered = try {
            runBlocking {
                val response = http.post(address) {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                response.status to response.bodyAsText()
            }
        } catch (givenUp: CancellationException) {
            throw givenUp
        } catch (unreached: Exception) {
            throw unreachable("no answer came back from the core at $address: $unreached", unreached)
        }

        val (status, body) = answered
        if (!status.isSuccess()) throw noReplyIn(status)
        return try {
            catalogJson.decodeFromString(body)
        } catch (unreadable: SerializationException) {
            throw unreachable("the core at $address answered something this cannot read: $unreadable", unreadable)
        }
    }

    private fun noReplyIn(status: HttpStatusCode): BreakdownException = unreachable(
        "the core at $address answered $status, which carries no reply to read",
        cause = null,
    )

    private fun unreachable(message: String, cause: Throwable?): BreakdownException =
        BreakdownException(BreakdownOrigin.CONNECTION, message, cause)
}
