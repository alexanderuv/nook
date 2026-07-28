package io.nook.contract

import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

/**
 * One thing the core was asked to do, as the values it was asked with.
 *
 * [values] holds the arguments in the order the operation takes them, minus the
 * project, and holds them whole rather than field by field: a command that gains
 * a field is compared with it, without any check being edited.
 */
public data class Invocation(
    public val operation: CatalogOperation,
    public val project: String?,
    public val values: List<Any?>,
)

/**
 * A core that records what it was asked and answers what a test told it to.
 *
 * An adapter owns no store and adds nothing to what the core decides, so what
 * its checks are about is entirely what crosses in each direction — which needs
 * a core that records what it was asked and hands back what it was told to.
 * What the eleven operations actually *do* belongs to the write and read
 * services and is checked against a real store where they live; running them
 * again through here would measure that work rather than the door's.
 *
 * It lives beside the contract rather than in either adapter because both need
 * it, and needing the same one is the point: two doors shown to agree while
 * being measured against stand-ins free to differ have been shown nothing.
 *
 * What each adapter's checks need beyond the record is where the two do differ,
 * and [before] is where that goes — it runs on every call once the call has been
 * recorded and before it is answered, so a subclass can refuse, break, or wait
 * without restating any of the eleven operations.
 */
public open class RecordingCore : OperationCatalog {

    /** Everything it was asked to do, in order. */
    public val invocations: MutableList<Invocation> = CopyOnWriteArrayList()

    /**
     * What every operation does with what it was asked. Answering with a value,
     * refusing by throwing, and breaking by throwing are all the test's to
     * choose.
     */
    public var answering: (Invocation) -> Any? = ::somethingOfTheRightShape

    /** Back to answering every operation with something of the shape it promises. */
    public fun answerNormally() {
        answering = ::somethingOfTheRightShape
    }

    /** Everything the stand-in was asked, of one operation alone. */
    public fun invocationsOf(operation: CatalogOperation): List<Invocation> =
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

    /**
     * What happens on a recorded call before it is answered. Nothing here; a
     * subclass overrides it to make the stand-in misbehave in whatever way its
     * own checks are about.
     */
    protected open fun before(invocation: Invocation) {
        // Recorded and answered, and nothing in between.
    }

    @Suppress("UNCHECKED_CAST")
    protected fun <R> answer(operation: CatalogOperation, project: String?, vararg values: Any?): R =
        asked(operation, project, values.toList()) as R

    protected fun nothing(operation: CatalogOperation, project: String?, vararg values: Any?) {
        asked(operation, project, values.toList())
    }

    private fun asked(operation: CatalogOperation, project: String?, values: List<Any?>): Any? {
        val invocation = Invocation(operation, project, values)
        invocations += invocation
        before(invocation)
        return answering(invocation)
    }
}

/**
 * An answer of the shape the operation promises, for every check whose subject
 * is what crossed rather than what came back.
 *
 * A wrong shape here would be caught as a call that produced no verdict rather
 * than as a wrong answer, which reads as the module under test breaking when
 * nothing of it did.
 */
public fun somethingOfTheRightShape(asked: Invocation): Any? = when (asked.operation) {
    CatalogOperation.LIST_ITEMS -> listOf(anItem())
    CatalogOperation.LIST_PROJECTS -> listOf(aProject())
    CatalogOperation.CREATE_PROJECT, CatalogOperation.GET_PROJECT -> aProject()
    CatalogOperation.CREATE_RELEASE, CatalogOperation.UPDATE_RELEASE -> aRelease()
    else -> anItem()
}

/** The core's own refusal when nothing answers to a project reference — from an address, or from a call. */
public fun noProject(ref: String): Nothing = throw StructuredErrorException(
    StructuredError(ErrorCode.NOT_FOUND, "no project matches reference \"$ref\"", Missing.PROJECT.asDetails()),
)

/**
 * What a core nobody can reach does when it is asked anything.
 *
 * It fails the way a call over a network fails — with nothing that could be
 * mistaken for the core having answered — which is the whole distinction a door
 * has to draw between an address that names nothing and a core that is not
 * there.
 */
public fun unreachableCore(): Nothing = throw IOException("connection refused")

// The entities a stand-in hands back. Every field is filled, so a check
// comparing a whole entity has a whole entity to compare.

private val aMoment: Instant = Instant.parse("2026-07-27T09:15:00.123456Z")

public fun aProject(
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

public fun anItem(
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

public fun aRelease(
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
