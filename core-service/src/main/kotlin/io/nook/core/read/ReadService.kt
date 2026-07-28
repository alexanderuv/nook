package io.nook.core.read

import io.nook.contract.ItemFilter
import io.nook.contract.ItemStatus
import io.nook.contract.ItemStatusSerializer
import io.nook.contract.ItemType
import io.nook.contract.ItemTypeSerializer
import io.nook.contract.ParentFilter
import io.nook.contract.Project
import io.nook.contract.ProjectItem
import io.nook.core.db.ItemDependencyTable
import io.nook.core.db.ProjectItemTable
import io.nook.core.db.ProjectTable
import io.nook.core.db.ReleaseTable
import io.nook.core.store.blockerSetsOf
import io.nook.core.store.blockersOf
import io.nook.core.store.resolveItem
import io.nook.core.store.resolveProject
import io.nook.core.store.resolveRelease
import io.nook.core.store.toProject
import io.nook.core.store.toProjectItem
import io.nook.core.store.validationFailed
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * The read path: the four ways structure leaves the store, and the only ones.
 *
 * Every operation opens one read-only transaction reading a single moment (see
 * [readTransaction]) and takes no lock. Nothing here writes, so nothing here can
 * conflict; a read fails only because the caller asked for something outside a
 * vocabulary — `validation_failed` — or for something that is not there —
 * `not_found`. The other two codes of the error model belong to writes and
 * cannot arise.
 *
 * Nothing here needs a clause about deleted rows, because deleting removes the
 * row: a deleted item is indistinguishable from one that never existed, by
 * construction rather than by a rule each operation has to remember. There is
 * correspondingly no argument, filter value, or operation by which a caller
 * could ask to see one.
 *
 * References work exactly as they do on the write path, through the same code: a
 * string in UUID form resolves as an id, anything else as a slug, and items and
 * releases resolve inside the bound project alone.
 */
class ReadService(private val db: Database) {

    fun getProject(projectRef: String): Project = readTransaction(db) {
        resolveProject(projectRef).toProject()
    }

    fun listProjects(): List<Project> = readTransaction(db) {
        ProjectTable.selectAll()
            .newestFirst(ProjectTable.createdAt, ProjectTable.id)
            .map { it.toProject() }
    }

    fun getItem(projectRef: String, itemRef: String): ProjectItem = readTransaction(db) {
        val projectId = resolveProject(projectRef)[ProjectTable.id]
        val row = resolveItem(projectId, itemRef)
        row.toProjectItem(blockersOf(row[ProjectItemTable.id]))
    }

    fun listItems(projectRef: String, filter: ItemFilter): List<ProjectItem> = readTransaction(db) {
        val projectId = resolveProject(projectRef)[ProjectTable.id]
        withBlockerSets(
            ProjectItemTable.selectAll()
                .where(itemsOf(projectId) and matchOf(projectId, filter))
                .newestFirst(ProjectItemTable.createdAt, ProjectItemTable.id)
                .toList(),
        )
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun itemsOf(projectId: Uuid): Op<Boolean> = ProjectItemTable.projectId eq projectId

    /**
     * Newest created first, with the id breaking a tie. Two rows can share a
     * creation instant, and without the tiebreak the order between them is
     * whatever the query plan happens to produce — so an identical call could
     * return a different order.
     */
    private fun Query.newestFirst(createdAt: Column<*>, id: Column<*>): Query =
        orderBy(createdAt to SortOrder.DESC, id to SortOrder.ASC)

    /**
     * Attaches each item's blockers, fetched with one query for the whole
     * listing rather than one per item — measurably faster, and it keeps the
     * answer inside the statements this transaction makes consistent.
     */
    private fun withBlockerSets(rows: List<ResultRow>): List<ProjectItem> {
        val blockerSets = blockerSetsOf(rows.map { it[ProjectItemTable.id] })
        return rows.map { it.toProjectItem(blockerSets[it[ProjectItemTable.id]].orEmpty()) }
    }

    /**
     * The filter as one condition. Every part is validated before the store is
     * asked anything: a value outside its vocabulary, a part supplied with no
     * values at all, and a parent value naming something that is not an epic are
     * all caller mistakes, and each is refused as one.
     */
    private fun matchOf(projectId: Uuid, filter: ItemFilter): Op<Boolean> {
        val parts = buildList {
            filter.types?.let { types ->
                val codes = requireValues(types, "type").map {
                    ItemTypeSerializer.of(it, ::validationFailed).code
                }
                add(ProjectItemTable.type inList codes)
            }
            filter.statuses?.let { statuses ->
                val codes = requireValues(statuses, "status").map {
                    ItemStatusSerializer.of(it, ::validationFailed).code
                }
                add(ProjectItemTable.status inList codes)
            }
            filter.parents?.let { add(parentMatch(projectId, requireValues(it, "parent"))) }
            filter.releases?.let { releases ->
                val releaseIds = requireValues(releases, "release").map {
                    resolveRelease(projectId, it)[ReleaseTable.id]
                }
                add(ProjectItemTable.releaseId inList releaseIds)
            }
            filter.heldUp?.let { add(heldUpMatch(projectId, it)) }
        }
        return parts.reduceOrNull { narrowed, part -> narrowed and part } ?: Op.TRUE
    }

    /**
     * Held up, or not: whether the item has a blocker that is neither `done`
     * nor `cancelled`. Answered from the dependency edges as they stand when the
     * question is asked — there is no stored readiness, and this part carries no
     * opinion about type or status, so it narrows alongside the others rather
     * than smuggling in a definition of its own.
     *
     * `not held up` is the complement rather than a second query, which is what
     * makes an item with no blockers at all come back: it is in no edge, so it is
     * in neither the subquery nor, therefore, the items it selects. Both columns
     * involved are non-null, so `NOT IN` cannot swallow a row on a null.
     */
    private fun heldUpMatch(projectId: Uuid, heldUp: Boolean): Op<Boolean> {
        val unfinished = ProjectItemTable.select(ProjectItemTable.id)
            .where {
                itemsOf(projectId) and
                    (ProjectItemTable.status notInList setOf(ItemStatus.DONE.code, ItemStatus.CANCELLED.code))
            }
        val blockedByUnfinished = ItemDependencyTable.select(ItemDependencyTable.itemId)
            .where { ItemDependencyTable.dependsOnId inSubQuery unfinished }
        return if (heldUp) {
            ProjectItemTable.id inSubQuery blockedByUnfinished
        } else {
            ProjectItemTable.id notInSubQuery blockedByUnfinished
        }
    }

    /** Any of the named epics, or no epic at all — whichever the caller supplied. */
    private fun parentMatch(projectId: Uuid, values: List<ParentFilter>): Op<Boolean> {
        val epicIds = values.filterIsInstance<ParentFilter.Epic>().map { epicIdOf(projectId, it.ref) }
        val matchesAnEpic = if (epicIds.isEmpty()) null else ProjectItemTable.parentId inList epicIds
        val matchesNoEpic =
            if (ParentFilter.NoEpic in values) ProjectItemTable.parentId.isNull() else null
        return listOfNotNull(matchesAnEpic, matchesNoEpic)
            .reduce { widened, alternative -> widened or alternative }
    }

    /**
     * Resolves [ref] to an epic, or fails. Nothing can sit under a leaf, so
     * asking what does is a caller mistake rather than an empty answer.
     */
    private fun epicIdOf(projectId: Uuid, ref: String): Uuid {
        val parent = resolveItem(projectId, ref)
        if (parent[ProjectItemTable.type] != ItemType.EPIC.code) {
            validationFailed("\"$ref\" is not an epic, and only an epic parents anything")
        }
        return parent[ProjectItemTable.id]
    }

    /**
     * A supplied part must carry at least one value. Matching any of nothing is
     * a mistake worth saying out loud: the query builder would fold it into a
     * condition that matches nothing and report success, which is the silent
     * wrong answer this check replaces. Leaving the part out entirely is how a
     * caller says "don't filter on this".
     */
    private fun <T> requireValues(values: List<T>, part: String): List<T> =
        values.ifEmpty {
            validationFailed(
                "the $part filter was supplied with no values; leave it out to not filter on $part",
            )
        }
}
