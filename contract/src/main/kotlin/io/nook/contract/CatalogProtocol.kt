package io.nook.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The eleven operations wired for the connection. Each states once what its
 * payload is, what its answer is, and how it is invoked on a catalog; both
 * halves read that one statement — the answering side runs a request against
 * it, the calling library builds a request from it — so the two cannot come to
 * disagree about a name or a shape.
 *
 * Whether an operation acts inside a project is the shape of the wiring rather
 * than a flag on it: [ProjectScoped] cannot be invoked without a project and
 * [InstanceLevel] has nowhere to put one, so the two ways a caller can get that
 * wrong are refused here, once, instead of in eleven places.
 */
internal sealed class WiredOperation<P, R>(
    val operation: CatalogOperation,
    val payloadShape: KSerializer<P>,
    val answerShape: AnswerShape<R>,
) {

    /**
     * Reads [payload] as this operation defines it, runs the operation, and
     * hands back what its answer carries — nothing, for the two deletes.
     *
     * A payload this operation cannot read throws out of here as a
     * [SerializationException]; the answering side maps it, with everything
     * else that cannot be read, to a refusal.
     */
    fun runAgainst(catalog: OperationCatalog, project: String?, payload: JsonObject): JsonElement? {
        val answer = invokeOn(catalog, project, catalogJson.decodeFromJsonElement(payloadShape, payload))
        return answerShape.write(catalogJson, answer)
    }

    protected abstract fun invokeOn(catalog: OperationCatalog, project: String?, payload: P): R

    /** An operation acting on the whole instance, which names no project to act inside. */
    class InstanceLevel<P, R>(
        operation: CatalogOperation,
        payloadShape: KSerializer<P>,
        answerShape: AnswerShape<R>,
        private val invoke: OperationCatalog.(P) -> R,
    ) : WiredOperation<P, R>(operation, payloadShape, answerShape) {

        override fun invokeOn(catalog: OperationCatalog, project: String?, payload: P): R {
            if (project != null) {
                refuseAsInvalid("${operation.label} acts on the whole instance and names no project")
            }
            return catalog.invoke(payload)
        }
    }

    /** An operation acting inside one project, which the request has to name. */
    class ProjectScoped<P, R>(
        operation: CatalogOperation,
        payloadShape: KSerializer<P>,
        answerShape: AnswerShape<R>,
        private val invoke: OperationCatalog.(String, P) -> R,
    ) : WiredOperation<P, R>(operation, payloadShape, answerShape) {

        override fun invokeOn(catalog: OperationCatalog, project: String?, payload: P): R {
            val inside = project ?: refuseAsInvalid("${operation.label} acts inside a project; name the project")
            return catalog.invoke(inside, payload)
        }
    }
}

/**
 * How an operation's answer crosses.
 *
 * Answering nothing is a shape of its own rather than an absent conversion: the
 * two deletes hand back no entity because what they removed no longer exists,
 * and saying that here is what keeps the calling library from having to pretend
 * a missing result is one of some other type.
 */
internal class AnswerShape<R> private constructor(
    val write: (Json, R) -> JsonElement?,
    val read: (Json, JsonElement?) -> R,
) {
    companion object {

        fun <R> of(shape: KSerializer<R>): AnswerShape<R> = AnswerShape(
            write = { json, value -> json.encodeToJsonElement(shape, value) },
            read = { json, carried ->
                json.decodeFromJsonElement(
                    shape,
                    carried ?: throw SerializationException("this answer carried no result to read"),
                )
            },
        )

        val nothing: AnswerShape<Unit> = AnswerShape({ _, _ -> null }, { _, _ -> })
    }
}

// The catalog, wired. Adding a twelfth operation to CatalogOperation stops this
// file compiling until it is wired here too, which is the point of looking it
// up through an exhaustive choice rather than a map that could quietly lack one.

internal val createProjectWiring = WiredOperation.InstanceLevel(
    CatalogOperation.CREATE_PROJECT,
    CreateProject.serializer(),
    AnswerShape.of(Project.serializer()),
) { command -> createProject(command) }

internal val getProjectWiring = WiredOperation.InstanceLevel(
    CatalogOperation.GET_PROJECT,
    TargetRef.serializer(),
    AnswerShape.of(Project.serializer()),
) { target -> getProject(target.ref) }

internal val listProjectsWiring = WiredOperation.InstanceLevel(
    CatalogOperation.LIST_PROJECTS,
    EmptyPayload.serializer(),
    AnswerShape.of(ListSerializer(Project.serializer())),
) { listProjects() }

internal val deleteProjectWiring = WiredOperation.InstanceLevel(
    CatalogOperation.DELETE_PROJECT,
    TargetRef.serializer(),
    AnswerShape.nothing,
) { target -> deleteProject(target.ref) }

internal val createItemWiring = WiredOperation.ProjectScoped(
    CatalogOperation.CREATE_ITEM,
    CreateItem.serializer(),
    AnswerShape.of(ProjectItem.serializer()),
) { project, command -> createItem(project, command) }

internal val updateItemWiring = WiredOperation.ProjectScoped(
    CatalogOperation.UPDATE_ITEM,
    ItemUpdate.serializer(),
    AnswerShape.of(ProjectItem.serializer()),
) { project, update -> updateItem(project, update.ref, update.changes) }

internal val deleteItemWiring = WiredOperation.ProjectScoped(
    CatalogOperation.DELETE_ITEM,
    TargetRef.serializer(),
    AnswerShape.nothing,
) { project, target -> deleteItem(project, target.ref) }

internal val createReleaseWiring = WiredOperation.ProjectScoped(
    CatalogOperation.CREATE_RELEASE,
    CreateRelease.serializer(),
    AnswerShape.of(Release.serializer()),
) { project, command -> createRelease(project, command) }

internal val updateReleaseWiring = WiredOperation.ProjectScoped(
    CatalogOperation.UPDATE_RELEASE,
    ReleaseUpdate.serializer(),
    AnswerShape.of(Release.serializer()),
) { project, update -> updateRelease(project, update.ref, update.changes) }

internal val getItemWiring = WiredOperation.ProjectScoped(
    CatalogOperation.GET_ITEM,
    TargetRef.serializer(),
    AnswerShape.of(ProjectItem.serializer()),
) { project, target -> getItem(project, target.ref) }

internal val listItemsWiring = WiredOperation.ProjectScoped(
    CatalogOperation.LIST_ITEMS,
    ItemFilter.serializer(),
    AnswerShape.of(ListSerializer(ProjectItem.serializer())),
) { project, filter -> listItems(project, filter) }

internal fun wiringOf(operation: CatalogOperation): WiredOperation<*, *> = when (operation) {
    CatalogOperation.CREATE_PROJECT -> createProjectWiring
    CatalogOperation.GET_PROJECT -> getProjectWiring
    CatalogOperation.LIST_PROJECTS -> listProjectsWiring
    CatalogOperation.DELETE_PROJECT -> deleteProjectWiring
    CatalogOperation.CREATE_ITEM -> createItemWiring
    CatalogOperation.UPDATE_ITEM -> updateItemWiring
    CatalogOperation.DELETE_ITEM -> deleteItemWiring
    CatalogOperation.CREATE_RELEASE -> createReleaseWiring
    CatalogOperation.UPDATE_RELEASE -> updateReleaseWiring
    CatalogOperation.GET_ITEM -> getItemWiring
    CatalogOperation.LIST_ITEMS -> listItemsWiring
}

/**
 * Runs [request] against this catalog, and returns what its answer carries —
 * nothing at all, for the two deletes.
 *
 * The three ways a request can be one this connection cannot read are refused
 * here as failed validations, and nothing reaches the store: an operation
 * nobody defined, a project-scoped call naming no project, and an
 * instance-level call naming one. A payload that cannot be read throws a
 * [SerializationException] instead, and the caller of this function maps that
 * — along with a request whose contents could not be read at all — to the same
 * refusal.
 *
 * Everything past that point is the core's own verdict. This applies no rule of
 * its own to a request it can read.
 */
public fun OperationCatalog.perform(request: CatalogRequest): JsonElement? {
    val operation = CatalogOperation.fromLabel(request.operation)
        ?: refuseAsInvalid("this connection carries no operation named \"${request.operation}\"")
    return wiringOf(operation).runAgainst(this, request.project, request.payload)
}

internal fun refuseAsInvalid(message: String): Nothing =
    throw StructuredErrorException(StructuredError(ErrorCode.VALIDATION_FAILED, message))
