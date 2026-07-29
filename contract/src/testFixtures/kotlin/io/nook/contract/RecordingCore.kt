package io.nook.contract

import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

/**
 * One thing the core was asked to do, as the values it was asked with and the
 * identity it was asked for.
 *
 * [values] holds the arguments in the order the operation takes them, minus the
 * project, and holds them whole rather than field by field: a command that gains
 * a field is compared with it, without any check being edited.
 */
public data class Invocation(
    public val operation: CatalogOperation,
    public val actor: Actor,
    public val project: String?,
    public val values: List<Any?>,
)

/**
 * A core that records what it was asked, who it was asked for, and answers what
 * a test told it to.
 *
 * An adapter owns no store and adds nothing to what the core decides, so what
 * its checks are about is entirely what crosses in each direction — which needs
 * a core that records what it was asked and hands back what it was told to.
 * What the eleven operations actually *do* belongs to the write and read
 * services and is checked against a real store where they live; running them
 * again through here would measure that work rather than the adapter's.
 *
 * It lives beside the contract rather than in either adapter because both need
 * it, and needing the same one is the point: two adapters shown to agree while
 * being measured against stand-ins free to differ have been shown nothing.
 *
 * What each adapter's checks need beyond the record is where the two do differ.
 * [before] runs on every call once it has been recorded and before it is
 * answered, so a subclass can refuse, break, or wait; [answerTo] is where a
 * subclass answers one operation for itself. Neither restates any of the eleven
 * operations.
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

    /** Everything it was asked on behalf of somebody, in order. */
    public val actors: List<Actor> get() = invocations.map { it.actor }

    /**
     * This core as one identity sees it — a small object per call, holding the
     * pair and nothing else.
     */
    final override fun forActor(actor: Actor): OperationCatalog = ActingFor(actor)

    // The eleven as a caller that bound nothing makes them, which is a call
    // naming nobody. Every one of them goes through a view, so there is one
    // place a call is recorded rather than two free to record it differently.

    override fun createProject(command: CreateProject): Project = unbound.createProject(command)

    override fun getProject(ref: String): Project = unbound.getProject(ref)

    override fun listProjects(): List<Project> = unbound.listProjects()

    override fun deleteProject(ref: String): Unit = unbound.deleteProject(ref)

    override fun createItem(projectRef: String, command: CreateItem): ProjectItem =
        unbound.createItem(projectRef, command)

    override fun updateItem(projectRef: String, itemRef: String, command: UpdateItem): ProjectItem =
        unbound.updateItem(projectRef, itemRef, command)

    override fun deleteItem(projectRef: String, itemRef: String): Unit = unbound.deleteItem(projectRef, itemRef)

    override fun createRelease(projectRef: String, command: CreateRelease): Release =
        unbound.createRelease(projectRef, command)

    override fun updateRelease(projectRef: String, releaseRef: String, command: UpdateRelease): Release =
        unbound.updateRelease(projectRef, releaseRef, command)

    override fun getItem(projectRef: String, itemRef: String): ProjectItem = unbound.getItem(projectRef, itemRef)

    override fun listItems(projectRef: String, filter: ItemFilter): List<ProjectItem> =
        unbound.listItems(projectRef, filter)

    /**
     * What happens on a recorded call before it is answered. Nothing here; a
     * subclass overrides it to make the stand-in misbehave in whatever way its
     * own checks are about.
     */
    protected open fun before(invocation: Invocation) {
        // Recorded and answered, and nothing in between.
    }

    /**
     * What a recorded call is answered with. A subclass overrides it where one
     * operation has an answer of its own to give, and leaves the rest to what a
     * test set — which is why it defers to [answering] rather than replacing it.
     */
    protected open fun answerTo(invocation: Invocation): Any? = answering(invocation)

    private val unbound: OperationCatalog = ActingFor(Actor.NOBODY)

    private fun asked(operation: CatalogOperation, actor: Actor, project: String?, values: List<Any?>): Any? {
        val invocation = Invocation(operation, actor, project, values)
        invocations += invocation
        before(invocation)
        return answerTo(invocation)
    }

    /** The eleven operations as one identity makes them, each recorded against it. */
    private inner class ActingFor(private val actor: Actor) : OperationCatalog {

        override fun forActor(actor: Actor): OperationCatalog = ActingFor(actor)

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
            asked(operation, actor, project, values.toList()) as R

        private fun nothing(operation: CatalogOperation, project: String?, vararg values: Any?) {
            asked(operation, actor, project, values.toList())
        }
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
 * mistaken for the core having answered — which is the whole distinction an adapter
 * has to draw between an address that names nothing and a core that is not
 * there.
 */
public fun unreachableCore(): Nothing = throw IOException("connection refused")

// The entities a stand-in hands back. Every field is filled, so a check
// comparing a whole entity has a whole entity to compare — the five naming who
// wrote a row among them.

private val aMoment: Instant = Instant.parse("2026-07-27T09:15:00.123456Z")

/** The person and the agent every stand-in entity is credited to. */
public const val A_PERSON: String = "alex"

public const val AN_AGENT: String = "claude-code"

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
    createdBy = A_PERSON,
    updatedBy = A_PERSON,
    createdByAgent = AN_AGENT,
    updatedByAgent = AN_AGENT,
    ownerSubject = A_PERSON,
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
    createdBy = A_PERSON,
    updatedBy = A_PERSON,
    createdByAgent = AN_AGENT,
    updatedByAgent = AN_AGENT,
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
    createdBy = A_PERSON,
    updatedBy = A_PERSON,
    createdByAgent = AN_AGENT,
    updatedByAgent = AN_AGENT,
)
