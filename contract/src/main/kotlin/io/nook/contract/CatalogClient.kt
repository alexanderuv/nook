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
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * says whether an answer arrived at all. A reply that arrived and cannot be read
 * here is the second of those and never the first: the operation ran, so there
 * is nothing in the call for its caller to correct.
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

    /** What every call is numbered by, so a reply can be shown to belong to the call that asked for it. */
    private val calls = AtomicLong()

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

    /**
     * One call, as the standard shapes it: the operation by name, the project
     * it acts inside beside that operation's own arguments, and an id nothing
     * but this call carries.
     *
     * The id is checked on the way back rather than trusted. Two calls sharing
     * one connection would otherwise be free to swap answers, and an answer
     * belonging to a different call is not a wrong answer to this one — it is
     * no answer to this one at all.
     */
    private fun <P, R> call(wiring: WiredOperation<P, R>, project: String?, payload: P): R {
        val id = JsonPrimitive(calls.incrementAndGet())
        val request = RpcRequest(
            method = wiring.operation.label,
            params = JsonObject(
                buildMap {
                    project?.let { put(PROJECT, JsonPrimitive(it)) }
                    putAll(catalogJson.encodeToJsonElement(wiring.payloadShape, payload).jsonObject)
                },
            ),
            id = id,
        )
        val reply = exchange(catalogJson.encodeToString(RpcRequestSerializer, request), id)
        return when (reply) {
            is RpcReply.Answered -> readAnswer(wiring, reply.result)
            is RpcReply.Failed -> throw reply.error.refusalOrBreakdown()
        }
    }

    /**
     * A failure the caller can act on, or one nobody can.
     *
     * What decides it is the name the failure gives itself, not its number: an
     * error naming one of the four domain failures is the core's verdict on the
     * request and goes back as one, and an error naming none of them settled
     * nothing — which must never read as something in the call to correct.
     */
    private fun RpcError.refusalOrBreakdown(): RuntimeException =
        asStructuredError()?.let { StructuredErrorException(it) }
            ?: brokenCore("the core at $address failed this call under $code: $message", null)

    /**
     * What a call that succeeded answered, or a breakdown where this build
     * cannot read it.
     *
     * A core a version ahead answers a write that landed with a value whose
     * vocabulary this build has never heard of. Left to escape, that reaches
     * whatever an adapter has arranged for a call it got wrong, and an agent is
     * told to correct the arguments of work that already happened — which it
     * corrects by sending the write a second time. The operation ran, so there
     * is nothing in the call for its caller to fix, and this says so.
     */
    private fun <P, R> readAnswer(wiring: WiredOperation<P, R>, result: JsonElement?): R =
        try {
            wiring.answerShape.read(catalogJson, result)
        } catch (unreadable: SerializationException) {
            throw brokenCore(
                "the core at $address answered this call with something this cannot read: $unreadable",
                unreadable,
            )
        }

    /**
     * Sends one request and reads back the one reply that answers it, or
     * reports why there was none. What separates the two origins is whether
     * anything came back at all: nothing listening, a link that dropped and a
     * wait that ran out are the connection, while a core that answered has
     * answered — even where what it answered is unreadable here, which it will
     * be again next time.
     */
    private fun exchange(request: String, id: JsonElement): RpcReply {
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
        val reply = try {
            catalogJson.decodeFromString(RpcReplySerializer, body)
        } catch (unreadable: SerializationException) {
            throw brokenCore("the core at $address answered something this cannot read: $unreadable", unreadable)
        }
        if (reply.id != id) {
            throw brokenCore("the core at $address answered ${reply.id} to the call this made as $id", null)
        }
        return reply
    }

    private fun noReplyIn(status: HttpStatusCode): BreakdownException = brokenCore(
        "the core at $address answered $status, which carries no reply to read",
        cause = null,
    )

    /**
     * The two origins, each carrying what was observed where a stack trace can
     * reach it. None of it is what the breakdown says out loud: which part of
     * Nook gave way is nobody's business outside this library.
     */
    private fun unreachable(observed: String, cause: Throwable?): BreakdownException =
        BreakdownException(BreakdownOrigin.CONNECTION, IllegalStateException(observed, cause))

    private fun brokenCore(observed: String, cause: Throwable?): BreakdownException =
        BreakdownException(BreakdownOrigin.CORE, IllegalStateException(observed, cause))
}
