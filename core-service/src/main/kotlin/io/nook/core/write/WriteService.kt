package io.nook.core.write

import io.nook.contract.AssignEpicToRelease
import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.CreateRelease
import io.nook.contract.FieldChange
import io.nook.contract.ItemStatus
import io.nook.contract.ItemType
import io.nook.contract.Project
import io.nook.contract.ProjectItem
import io.nook.contract.Release
import io.nook.contract.ReleaseStatus
import io.nook.contract.SetItemBlockedBy
import io.nook.contract.UpdateItem
import io.nook.contract.UpdateRelease
import io.nook.core.db.ItemDependencyTable
import io.nook.core.db.ProjectItemTable
import io.nook.core.db.ProjectTable
import io.nook.core.db.ReleaseTable
import io.nook.core.store.blockerSetsOf
import io.nook.core.store.blockersOf
import io.nook.core.store.notFound
import io.nook.core.store.resolveItem
import io.nook.core.store.resolveProject
import io.nook.core.store.resolveRelease
import io.nook.core.store.toProject
import io.nook.core.store.toProjectItem
import io.nook.core.store.toRelease
import io.nook.core.store.validationFailed
import java.time.Instant
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * The single write path: every mutation of the structure store goes through
 * one of the nine public operations below. Each takes the references that
 * address its target as parameters and the payload as a command data class
 * from the contract. Blocker edges change only by whole-set replacement, never
 * through any public delete.
 *
 * Every operation runs as one fresh transaction that takes an advisory lock
 * first — the project's own lock, or the instance-wide lock where the scope at
 * stake is the whole instance — so writers in one scope take turns. Validation
 * happens inside that lock; the schema's constraints stay on underneath as a
 * backstop, translated to structured errors if ever hit. A failure throws
 * [io.nook.contract.StructuredErrorException] and rolls the transaction back,
 * leaving no partial effect.
 *
 * Deleting removes the row. There is no mark, no trash, and no way back: the
 * store holds what exists and nothing else, so every other query here needs no
 * clause about deletion and no rule can be enforced against something a caller
 * cannot see. What a delete reaches is bounded by the schema's own cascades,
 * plus the children an epic must take with it, which no cascade covers.
 *
 * References: a string in UUID form resolves as an id, anything else as a
 * slug in the target project. Enum-valued inputs (item type, statuses) arrive
 * as their label strings and are validated against the vocabulary.
 */
class WriteService(private val db: Database) {

    fun createProject(command: CreateProject): Project =
        writeTransaction(db) {
            takeInstanceLock()
            requireUsableName(command.name)
            val chosenSlug = chooseSlug(
                command.slug, command.name,
                takenProjectSlugs(command.slug ?: deriveSlug(command.name)),
            )
            val id = Uuid.random()
            ProjectTable.insert {
                it[ProjectTable.id] = id
                it[ProjectTable.slug] = chosenSlug
                it[ProjectTable.name] = command.name
                if (command.description != null) it[ProjectTable.description] = command.description
            }
            loadProject(id)
        }

    fun createItem(projectRef: String, command: CreateItem): ProjectItem = writeTransaction(db) {
        val projectId = lockedProjectId(projectRef)
        val itemType = ItemType.fromLabel(command.type)
            ?: validationFailed("\"${command.type}\" is not an item type; the item types are ${ItemType.entries.joinToString { it.label }}")
        requireUsableName(command.name)
        val parentRef = command.parentRef
        val parentId = when {
            parentRef == null -> null
            itemType == ItemType.EPIC -> validationFailed("an epic never has a parent")
            else -> epicIdOf(projectId, parentRef)
        }
        val releaseRef = command.releaseRef
        val releaseId = when {
            releaseRef == null -> null
            itemType.isLeaf -> validationFailed("release assignment applies to epics only")
            else -> resolveRelease(projectId, releaseRef)[ReleaseTable.id]
        }
        val chosenSlug = chooseSlug(
            command.slug, command.name,
            takenItemSlugs(projectId, command.slug ?: deriveSlug(command.name)),
        )
        val id = Uuid.random()
        ProjectItemTable.insert {
            it[ProjectItemTable.id] = id
            it[ProjectItemTable.projectId] = projectId
            it[ProjectItemTable.parentId] = parentId
            it[ProjectItemTable.releaseId] = releaseId
            it[ProjectItemTable.type] = itemType.code
            it[ProjectItemTable.slug] = chosenSlug
            it[ProjectItemTable.name] = command.name
            if (command.description != null) it[ProjectItemTable.description] = command.description
            it[ProjectItemTable.status] = ItemStatus.TODO.code
        }
        loadItem(id)
    }

    fun updateItem(projectRef: String, itemRef: String, command: UpdateItem): ProjectItem =
        writeTransaction(db) {
            val projectId = lockedProjectId(projectRef)
            val row = resolveItem(projectId, itemRef)
            val itemId = row[ProjectItemTable.id]
            val currentType = ItemType.fromCode(row[ProjectItemTable.type])
                ?: error("the store holds item type code ${row[ProjectItemTable.type]}, which no member carries")

            val newName = withSetValue(command.name) { requireUsableName(it) }
            val newSlug = withSetValue(command.slug) { value ->
                explicitSlugProblem(value)?.let { validationFailed(it) }
                if (value != row[ProjectItemTable.slug] && value in takenItemSlugs(projectId, value)) {
                    conflict("slug \"$value\" is already taken by another item in the project")
                }
            }
            val newStatus = when (val status = command.status) {
                FieldChange.Keep -> null
                is FieldChange.Set -> ItemStatus.fromLabel(status.value)
                    ?: validationFailed("\"${status.value}\" is not an item status; the item statuses are ${ItemStatus.entries.joinToString { it.label }}")
            }
            val targetType = when (val type = command.type) {
                FieldChange.Keep -> currentType
                is FieldChange.Set -> ItemType.fromLabel(type.value)
                    ?: validationFailed("\"${type.value}\" is not an item type; the item types are ${ItemType.entries.joinToString { it.label }}")
            }
            val targetParentId = when (val parentRef = command.parentRef) {
                FieldChange.Keep -> row[ProjectItemTable.parentId]
                is FieldChange.Set -> parentRef.value?.let { epicIdOf(projectId, it) }
            }

            if (targetType == ItemType.EPIC && targetParentId != null) {
                validationFailed("an epic never has a parent")
            }
            if (targetType == ItemType.EPIC && currentType.isLeaf) {
                val inAnyEdge = ItemDependencyTable.selectAll()
                    .where { (ItemDependencyTable.itemId eq itemId) or (ItemDependencyTable.dependsOnId eq itemId) }
                    .any()
                if (inAnyEdge) {
                    validationFailed("an item in any dependency edge cannot become an epic; clear the edges first")
                }
            }
            if (currentType == ItemType.EPIC && targetType.isLeaf) {
                val hasChildren = ProjectItemTable.selectAll()
                    .where { ProjectItemTable.parentId eq itemId }
                    .any()
                if (hasChildren) {
                    validationFailed("an epic with child items cannot become a leaf; reparent the children first")
                }
                if (row[ProjectItemTable.releaseId] != null) {
                    validationFailed("an epic assigned to a release cannot become a leaf; unassign it first")
                }
            }

            ProjectItemTable.update({ ProjectItemTable.id eq itemId }) {
                if (newName != null) it[ProjectItemTable.name] = newName
                if (newSlug != null) it[ProjectItemTable.slug] = newSlug
                val description = command.description
                if (description is FieldChange.Set) it[ProjectItemTable.description] = description.value
                if (newStatus != null) it[ProjectItemTable.status] = newStatus.code
                if (command.type is FieldChange.Set) it[ProjectItemTable.type] = targetType.code
                if (command.parentRef is FieldChange.Set) it[ProjectItemTable.parentId] = targetParentId
                it[ProjectItemTable.updatedAt] = Instant.now()
            }
            loadItem(itemId)
        }

    fun setItemBlockedBy(projectRef: String, itemRef: String, command: SetItemBlockedBy): ProjectItem =
        writeTransaction(db) {
            val projectId = lockedProjectId(projectRef)
            val row = resolveItem(projectId, itemRef)
            val itemId = row[ProjectItemTable.id]
            if (row[ProjectItemTable.type] == ItemType.EPIC.code) {
                validationFailed("blockers apply to leaves; the target item is an epic")
            }
            val blockerIds = command.blockerRefs.map { ref ->
                val blocker = resolveItem(projectId, ref)
                if (blocker[ProjectItemTable.type] == ItemType.EPIC.code) {
                    validationFailed("a blocker must be a leaf; \"$ref\" is an epic")
                }
                blocker[ProjectItemTable.id]
            }.toSet()
            if (itemId in blockerIds) validationFailed("an item cannot block itself")
            if (wouldCreateCycle(projectBlockerEdges(projectId), itemId, blockerIds)) {
                cycleRejected("the supplied blockers would close a dependency loop")
            }
            ItemDependencyTable.deleteWhere { ItemDependencyTable.itemId eq itemId }
            blockerIds.forEach { blockerId ->
                ItemDependencyTable.insert {
                    it[ItemDependencyTable.itemId] = itemId
                    it[ItemDependencyTable.dependsOnId] = blockerId
                }
            }
            ProjectItemTable.update({ ProjectItemTable.id eq itemId }) {
                it[ProjectItemTable.updatedAt] = Instant.now()
            }
            loadItem(itemId)
        }

    fun createRelease(projectRef: String, command: CreateRelease): Release = writeTransaction(db) {
        val projectId = lockedProjectId(projectRef)
        requireUsableName(command.name)
        val chosenSlug = chooseSlug(
            command.slug, command.name,
            takenReleaseSlugs(projectId, command.slug ?: deriveSlug(command.name)),
        )
        val id = Uuid.random()
        ReleaseTable.insert {
            it[ReleaseTable.id] = id
            it[ReleaseTable.projectId] = projectId
            it[ReleaseTable.slug] = chosenSlug
            it[ReleaseTable.name] = command.name
            if (command.description != null) it[ReleaseTable.description] = command.description
            it[ReleaseTable.status] = ReleaseStatus.PLANNED.code
            it[ReleaseTable.targetDate] = command.targetDate
        }
        loadRelease(id)
    }

    fun updateRelease(projectRef: String, releaseRef: String, command: UpdateRelease): Release =
        writeTransaction(db) {
            val projectId = lockedProjectId(projectRef)
            val row = resolveRelease(projectId, releaseRef)
            val releaseId = row[ReleaseTable.id]

            val newName = withSetValue(command.name) { requireUsableName(it) }
            val newSlug = withSetValue(command.slug) { value ->
                explicitSlugProblem(value)?.let { validationFailed(it) }
                if (value != row[ReleaseTable.slug] && value in takenReleaseSlugs(projectId, value)) {
                    conflict("slug \"$value\" is already taken by another release in the project")
                }
            }
            val newStatus = when (val status = command.status) {
                FieldChange.Keep -> null
                is FieldChange.Set -> ReleaseStatus.fromLabel(status.value)
                    ?: validationFailed("\"${status.value}\" is not a release status; the release statuses are ${ReleaseStatus.entries.joinToString { it.label }}")
            }

            ReleaseTable.update({ ReleaseTable.id eq releaseId }) {
                if (newName != null) it[ReleaseTable.name] = newName
                if (newSlug != null) it[ReleaseTable.slug] = newSlug
                val description = command.description
                if (description is FieldChange.Set) it[ReleaseTable.description] = description.value
                if (newStatus != null) it[ReleaseTable.status] = newStatus.code
                val targetDate = command.targetDate
                if (targetDate is FieldChange.Set) it[ReleaseTable.targetDate] = targetDate.value
                it[ReleaseTable.updatedAt] = Instant.now()
            }
            loadRelease(releaseId)
        }

    fun assignEpicToRelease(projectRef: String, epicRef: String, command: AssignEpicToRelease): ProjectItem =
        writeTransaction(db) {
            val projectId = lockedProjectId(projectRef)
            val row = resolveItem(projectId, epicRef)
            if (row[ProjectItemTable.type] != ItemType.EPIC.code) {
                validationFailed("release assignment applies to epics only")
            }
            val releaseId = command.releaseRef?.let { resolveRelease(projectId, it)[ReleaseTable.id] }
            ProjectItemTable.update({ ProjectItemTable.id eq row[ProjectItemTable.id] }) {
                it[ProjectItemTable.releaseId] = releaseId
                it[ProjectItemTable.updatedAt] = Instant.now()
            }
            loadItem(row[ProjectItemTable.id])
        }

    /**
     * Removes an item, and an epic's children with it — nothing may survive a
     * deletion above it. Its blocker edges go too, by the schema's cascade,
     * whichever end of the edge it sat on. Nothing is returned: the item does
     * not exist once this commits, so there is no entity left to hand back.
     *
     * Children are removed explicitly because the parent link deliberately does
     * not cascade: `project → project_item` is the schema's single cascade path,
     * which is what keeps deletion's reach something this code states rather
     * than something the reader has to derive from a graph of foreign keys.
     *
     * Deleting something already deleted is [io.nook.contract.ErrorCode.NOT_FOUND],
     * the same as any other reference to a row that is not there.
     */
    fun deleteItem(projectRef: String, itemRef: String): Unit = writeTransaction(db) {
        val projectId = lockedProjectId(projectRef)
        val row = resolveItem(projectId, itemRef)
        val itemId = row[ProjectItemTable.id]
        if (row[ProjectItemTable.type] == ItemType.EPIC.code) {
            ProjectItemTable.deleteWhere { ProjectItemTable.parentId eq itemId }
        }
        ProjectItemTable.deleteWhere { ProjectItemTable.id eq itemId }
    }

    /**
     * Removes a project and, by the schema's cascade from it, every release,
     * item, blocker edge, and document row inside it — one statement, one
     * transaction, nothing left behind.
     *
     * Two locks, in this order. The instance-wide one because a project's
     * handle is unique across the instance, and freeing one must not race a
     * creation scanning those handles. The project's own one because a writer
     * already inside it would otherwise be working in a project that is being
     * removed underneath it. No other operation holds both, so the pair cannot
     * deadlock against anything.
     */
    fun deleteProject(projectRef: String): Unit = writeTransaction(db) {
        takeInstanceLock()
        val projectId = resolveProject(projectRef)[ProjectTable.id]
        takeProjectLock(projectId)
        ProjectTable.deleteWhere { ProjectTable.id eq projectId }
    }

    // ── shared internals ─────────────────────────────────────────────────────

    /**
     * Resolves the project reference, takes its lock, and returns its id.
     *
     * The project is read twice on purpose. The first read is what the lock key
     * is derived from, and it necessarily happens before the lock is held — so
     * the project can be deleted in the gap. The second read is inside the lock,
     * which is the only place an answer about the project holds still. Without
     * it the write goes ahead against a project that is gone and the store
     * refuses it on a foreign key, which reaches the caller as a fault in this
     * service rather than as the `not_found` it actually is.
     */
    private fun JdbcTransaction.lockedProjectId(projectRef: String): Uuid {
        val projectId = resolveProject(projectRef)[ProjectTable.id]
        takeProjectLock(projectId)
        val stillThere = ProjectTable.selectAll().where { ProjectTable.id eq projectId }.any()
        if (!stillThere) notFound("no project matches reference \"$projectRef\"")
        return projectId
    }

    /** Resolves [ref] to an epic in the project, or fails: leaves cannot parent. */
    private fun epicIdOf(projectId: Uuid, ref: String): Uuid {
        val parent = resolveItem(projectId, ref)
        if (parent[ProjectItemTable.type] != ItemType.EPIC.code) {
            validationFailed("the parent of a leaf must be an epic")
        }
        return parent[ProjectItemTable.id]
    }

    private fun requireUsableName(name: String) {
        if (name.isBlank()) validationFailed("a name must not be empty or only whitespace")
    }

    /**
     * The slug for a new entity: an explicit slug is validated and must be
     * free (never suffixed); a derived slug takes the first free numeric
     * suffix on collision. [taken] holds the slugs already in use in the
     * uniqueness scope that could collide with the candidate.
     */
    private fun chooseSlug(explicit: String?, name: String, taken: Set<String>): String =
        if (explicit != null) {
            explicitSlugProblem(explicit)?.let { validationFailed(it) }
            if (explicit in taken) conflict("slug \"$explicit\" is already taken in its scope")
            explicit
        } else {
            val base = deriveSlug(name)
            if (base.isEmpty()) {
                validationFailed("the name \"$name\" yields no usable slug; supply a slug explicitly")
            }
            firstFreeSlug(base, taken)
        }

    // The slugs already in use in a uniqueness scope. Every row in the scope is
    // a row that exists, so a name freed by a delete is free at once and needs
    // no clause saying so.

    private fun takenProjectSlugs(prefix: String): Set<String> =
        ProjectTable.select(ProjectTable.slug)
            .where { (ProjectTable.slug eq prefix) or (ProjectTable.slug like "$prefix-%") }
            .map { it[ProjectTable.slug] }
            .toSet()

    private fun takenItemSlugs(projectId: Uuid, prefix: String): Set<String> =
        ProjectItemTable.select(ProjectItemTable.slug)
            .where {
                (ProjectItemTable.projectId eq projectId) and
                    ((ProjectItemTable.slug eq prefix) or (ProjectItemTable.slug like "$prefix-%"))
            }
            .map { it[ProjectItemTable.slug] }
            .toSet()

    private fun takenReleaseSlugs(projectId: Uuid, prefix: String): Set<String> =
        ReleaseTable.select(ReleaseTable.slug)
            .where {
                (ReleaseTable.projectId eq projectId) and
                    ((ReleaseTable.slug eq prefix) or (ReleaseTable.slug like "$prefix-%"))
            }
            .map { it[ReleaseTable.slug] }
            .toSet()

    /** Every blocker edge among the project's items, keyed by the blocked item. */
    private fun projectBlockerEdges(projectId: Uuid): Map<Uuid, Set<Uuid>> =
        blockerSetsOf(
            ProjectItemTable.select(ProjectItemTable.id)
                .where { ProjectItemTable.projectId eq projectId }
                .map { it[ProjectItemTable.id] },
        )

    /** Runs [validate] on a set value and returns it; null when the field is kept. */
    private fun <T : Any> withSetValue(change: FieldChange<T>, validate: (T) -> Unit): T? =
        when (change) {
            FieldChange.Keep -> null
            is FieldChange.Set -> change.value.also(validate)
        }

    private fun loadProject(id: Uuid): Project =
        ProjectTable.selectAll().where { ProjectTable.id eq id }.first().toProject()

    private fun loadItem(id: Uuid): ProjectItem =
        ProjectItemTable.selectAll().where { ProjectItemTable.id eq id }.first()
            .toProjectItem(blockersOf(id))

    private fun loadRelease(id: Uuid): Release =
        ReleaseTable.selectAll().where { ReleaseTable.id eq id }.first().toRelease()
}
