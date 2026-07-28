package io.nook.mcp

import io.nook.contract.CatalogOperation
import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.CreateRelease
import io.nook.contract.ErrorCode
import io.nook.contract.ItemFilter
import io.nook.contract.ItemStatus
import io.nook.contract.ItemType
import io.nook.contract.Missing
import io.nook.contract.OperationCatalog
import io.nook.contract.Project
import io.nook.contract.ProjectItem
import io.nook.contract.Release
import io.nook.contract.ReleaseStatus
import io.nook.contract.StructuredError
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateItem
import io.nook.contract.UpdateRelease
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

/**
 * One thing the core was asked to do, as the values it was asked with.
 *
 * [values] holds the arguments in the order the operation takes them, minus the
 * project, and holds them whole rather than field by field: a command that
 * gains a field is compared with it, without any check here being edited.
 */
data class Invocation(val operation: CatalogOperation, val project: String?, val values: List<Any?>)

/**
 * A core that answers what a test tells it to answer.
 *
 * The server owns no store and adds nothing to what the core decides, so what
 * these tests are about is entirely what crosses in each direction — which
 * needs a core that records what it was asked and hands back what it was told
 * to. What the seven operations actually *do* belongs to the write and read
 * services and is tested against a real store where they live; running them
 * again through here would measure that work rather than this.
 */
class StandInCore : OperationCatalog {

    /** The projects this stand-in knows, each reachable by its handle and by its id. */
    val projects: MutableList<Project> = CopyOnWriteArrayList()

    /** Everything it was asked to do, in order. */
    val invocations: MutableList<Invocation> = CopyOnWriteArrayList()

    /**
     * What every operation but `get_project` does with what it was asked.
     * Answering with a value, refusing by throwing, and breaking by throwing
     * are all the test's to choose.
     */
    var answering: (Invocation) -> Any? = ::somethingOfTheRightShape

    /** Back to answering every operation with something of the shape it promises. */
    fun answerNormally() {
        answering = ::somethingOfTheRightShape
    }

    /** Where a project comes from. Set by a test that wants the core unreachable, or slow. */
    var resolving: (String) -> Project = { ref ->
        projects.firstOrNull { it.slug == ref || it.id.toString() == ref } ?: noProject(ref)
    }

    /** Everything the stand-in was asked, of one operation alone. */
    fun invocationsOf(operation: CatalogOperation): List<Invocation> =
        invocations.filter { it.operation == operation }

    /**
     * Everything that reached the core through a tool.
     *
     * The one question the door itself asks — which project an address names —
     * is not a tool call and is left out, so that "nothing reached the core" can
     * be said about a refused connection without it meaning "the door never
     * asked", which is a different claim.
     */
    val toolCalls: List<Invocation> get() = invocations.filterNot { it.operation == CatalogOperation.GET_PROJECT }

    /** How many times the door asked which project an address names. */
    val projectQuestions: List<Invocation> get() = invocationsOf(CatalogOperation.GET_PROJECT)

    override fun createProject(command: CreateProject): Project =
        answer(CatalogOperation.CREATE_PROJECT, null, command)

    override fun getProject(ref: String): Project {
        invocations += Invocation(CatalogOperation.GET_PROJECT, null, listOf(ref))
        return resolving(ref)
    }

    override fun listProjects(): List<Project> = answer(CatalogOperation.LIST_PROJECTS, null)

    override fun deleteProject(ref: String): Unit = nothing(CatalogOperation.DELETE_PROJECT, null, ref)

    override fun createItem(projectRef: String, command: CreateItem): ProjectItem =
        answer(CatalogOperation.CREATE_ITEM, projectRef, command)

    override fun updateItem(projectRef: String, itemRef: String, command: UpdateItem): ProjectItem =
        answer(CatalogOperation.UPDATE_ITEM, projectRef, itemRef, command)

    override fun deleteItem(projectRef: String, itemRef: String): Unit =
        nothing(CatalogOperation.DELETE_ITEM, projectRef, itemRef)

    override fun createRelease(projectRef: String, command: CreateRelease): Release =
        answer(CatalogOperation.CREATE_RELEASE, projectRef, command)

    override fun updateRelease(projectRef: String, releaseRef: String, command: UpdateRelease): Release =
        answer(CatalogOperation.UPDATE_RELEASE, projectRef, releaseRef, command)

    override fun getItem(projectRef: String, itemRef: String): ProjectItem =
        answer(CatalogOperation.GET_ITEM, projectRef, itemRef)

    override fun listItems(projectRef: String, filter: ItemFilter): List<ProjectItem> =
        answer(CatalogOperation.LIST_ITEMS, projectRef, filter)

    @Suppress("UNCHECKED_CAST")
    private fun <R> answer(operation: CatalogOperation, project: String?, vararg values: Any?): R =
        asked(operation, project, values) as R

    private fun nothing(operation: CatalogOperation, project: String?, vararg values: Any?) {
        asked(operation, project, values)
    }

    private fun asked(operation: CatalogOperation, project: String?, values: Array<out Any?>): Any? {
        val invocation = Invocation(operation, project, values.toList())
        invocations += invocation
        // A core asked to work inside a project that is not there refuses before
        // it looks at anything else, whatever it was asked to do. Which is what
        // makes a project disappearing under a connected agent something a test
        // can arrange by deleting it, rather than by scripting a refusal.
        if (project != null && projects.none { it.id.toString() == project }) noProject(project)
        return answering(invocation)
    }
}

/**
 * An answer of the shape the operation promises, for every test whose subject is
 * what crossed rather than what came back.
 *
 * A wrong shape here would be caught as a fault rather than as a wrong answer,
 * which reads as this module breaking when nothing of it did.
 */
fun somethingOfTheRightShape(asked: Invocation): Any? = when (asked.operation) {
    CatalogOperation.LIST_ITEMS -> listOf(anItem())
    CatalogOperation.LIST_PROJECTS -> listOf(aProject())
    CatalogOperation.CREATE_PROJECT, CatalogOperation.GET_PROJECT -> aProject()
    CatalogOperation.CREATE_RELEASE, CatalogOperation.UPDATE_RELEASE -> aRelease()
    else -> anItem()
}

/** The core's own refusal when nothing answers to a project reference — from an address, or from a call. */
fun noProject(ref: String): Nothing = throw StructuredErrorException(
    StructuredError(ErrorCode.NOT_FOUND, "no project matches reference \"$ref\"", Missing.PROJECT.asDetails()),
)

/**
 * What a core nobody can reach does when it is asked anything.
 *
 * It fails the way a call over a network fails — with nothing that could be
 * mistaken for the core having answered — which is the whole distinction the
 * door has to draw between an address that names nothing and a core that is not
 * there.
 */
fun unreachableCore(): Nothing = throw IOException("connection refused")

// The entities a stand-in hands back. Every field is filled, so a check
// comparing a whole entity has a whole entity to compare.

private val aMoment: Instant = Instant.parse("2026-07-27T09:15:00.123456Z")

fun aProject(
    name: String = "Search revamp",
    slug: String = "search-revamp",
    description: String? = "Rebuild search so it stops timing out.",
    id: Uuid = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000001"),
): Project = Project(
    id = id,
    slug = slug,
    name = name,
    description = description,
    artifactRepoUrl = "https://example.invalid/$slug.git",
    createdAt = aMoment,
    updatedAt = aMoment,
)

fun anItem(
    name: String = "Add search",
    slug: String = "add-search",
    id: Uuid = Uuid.parse("cccccccc-0000-0000-0000-000000000001"),
    projectId: Uuid = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000001"),
): ProjectItem = ProjectItem(
    id = id,
    projectId = projectId,
    parentId = Uuid.parse("cccccccc-0000-0000-0000-00000000000e"),
    releaseId = Uuid.parse("dddddddd-0000-0000-0000-000000000001"),
    type = ItemType.TASK,
    slug = slug,
    name = name,
    description = "Two lines,\nand \"quotation marks\"",
    status = ItemStatus.TODO,
    blockedBy = setOf(Uuid.parse("cccccccc-0000-0000-0000-000000000002")),
    createdAt = aMoment,
    updatedAt = aMoment,
)

fun aRelease(
    name: String = "Autumn",
    slug: String = "autumn",
    id: Uuid = Uuid.parse("dddddddd-0000-0000-0000-000000000001"),
): Release = Release(
    id = id,
    projectId = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000001"),
    slug = slug,
    name = name,
    description = "Everything shipping before the end of the year",
    status = ReleaseStatus.PLANNED,
    targetDate = java.time.LocalDate.of(2026, 12, 24),
    createdAt = aMoment,
    updatedAt = aMoment,
)
