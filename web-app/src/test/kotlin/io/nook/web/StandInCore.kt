package io.nook.web

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
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
 * A core that answers what a test tells it to answer, and misbehaves when a
 * call names one of the references below.
 *
 * The app in front of it owns no store and adds nothing to what the core
 * decides, so what these checks are about is entirely what crosses in each
 * direction — which needs a core that records what it was asked and hands back
 * what it was told to. What the eleven operations actually *do* belongs to the
 * write and read services and is checked against a real store where they live.
 *
 * The misbehaviours ride on the reference a call names rather than on a switch,
 * so one request written once can be sent to both doors and produce the same
 * ending at each without anything being set up in between.
 */
class StandInCore : OperationCatalog {

    /** Everything it was asked to do, in order. */
    val invocations: MutableList<Invocation> = CopyOnWriteArrayList()

    /** What every operation does with what it was asked, where no reference says otherwise. */
    var answering: (Invocation) -> Any? = ::somethingOfTheRightShape

    /** How long a call naming [SLOW] sits before it answers. */
    var patience: Duration = 100.milliseconds

    /** Back to answering every operation with something of the shape it promises. */
    fun answerNormally() {
        answering = ::somethingOfTheRightShape
    }

    /** Everything the stand-in was asked, of one operation alone. */
    fun invocationsOf(operation: CatalogOperation): List<Invocation> =
        invocations.filter { it.operation == operation }

    override fun createProject(command: CreateProject): Project =
        answer(CatalogOperation.CREATE_PROJECT, null, command)

    override fun getProject(ref: String): Project = answer(CatalogOperation.GET_PROJECT, null, ref)

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
        (listOfNotNull(project) + values.filterIsInstance<String>()).forEach(::actOn)
        return answering(invocation)
    }

    /** What a call naming [reference] gets instead of an answer, where the reference asks for one. */
    private fun actOn(reference: String) {
        when (reference) {
            NOT_THERE -> refuse(ErrorCode.NOT_FOUND, "nothing answers to \"$NOT_THERE\"", Missing.ITEM.asDetails())
            TAKEN -> refuse(ErrorCode.CONFLICT, "\"$TAKEN\" is already held by something else")
            A_LOOP -> refuse(ErrorCode.CYCLE, "\"$A_LOOP\" would wait on itself")
            NOT_ALLOWED -> refuse(ErrorCode.VALIDATION_FAILED, "\"$NOT_ALLOWED\" is not a thing to ask for")
            GONE -> refuse(ErrorCode.NOT_FOUND, "no project answers to \"$GONE\"", Missing.PROJECT.asDetails())
            A_DEFECT -> error("a defect planted inside the core")
            SLOW -> Thread.sleep(patience.inWholeMilliseconds)
            SILENT -> CountDownLatch(1).await()
        }
    }

    private fun refuse(code: ErrorCode, said: String, details: Map<String, String>? = null): Nothing =
        throw StructuredErrorException(StructuredError(code, said, details))
}

/** The references a call names to be refused, broken on, kept waiting, or never answered. */
const val NOT_THERE: String = "not-there"

const val TAKEN: String = "taken"

const val A_LOOP: String = "a-loop"

const val NOT_ALLOWED: String = "not-allowed"

const val GONE: String = "gone"

const val A_DEFECT: String = "a-defect"

const val SLOW: String = "slow"

const val SILENT: String = "silent"

/**
 * An answer of the shape the operation promises, for every check whose subject
 * is what crossed rather than what came back.
 *
 * A wrong shape here would be caught as a call that produced no verdict rather
 * than as a wrong answer, which reads as this module breaking when nothing of
 * it did.
 */
fun somethingOfTheRightShape(asked: Invocation): Any? = when (asked.operation) {
    CatalogOperation.LIST_ITEMS -> listOf(anItem())
    CatalogOperation.LIST_PROJECTS -> listOf(aProject())
    CatalogOperation.CREATE_PROJECT, CatalogOperation.GET_PROJECT -> aProject()
    CatalogOperation.CREATE_RELEASE, CatalogOperation.UPDATE_RELEASE -> aRelease()
    else -> anItem()
}

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
): ProjectItem = ProjectItem(
    id = id,
    projectId = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000001"),
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
    targetDate = LocalDate.of(2026, 12, 24),
    createdAt = aMoment,
    updatedAt = aMoment,
)
