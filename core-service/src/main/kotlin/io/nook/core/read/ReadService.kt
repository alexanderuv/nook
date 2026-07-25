package io.nook.core.read

import io.nook.contract.ItemFilter
import io.nook.contract.ItemStatus
import io.nook.contract.ItemType
import io.nook.contract.ParentFilter
import io.nook.contract.Project
import io.nook.contract.ProjectItem
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
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * The read path: the five ways structure leaves the store, and the only ones.
 *
 * Every operation opens one read-only transaction reading a single moment (see
 * [readTransaction]) and takes no lock. Nothing here writes, so nothing here can
 * conflict; a read fails only because the caller asked for something outside a
 * vocabulary — `validation_failed` — or for something that is not there —
 * `not_found`. The other two codes of the error model belong to writes and
 * cannot arise.
 *
 * Only live rows are ever returned or resolved, and there is no argument by
 * which a caller could ask otherwise. That absence is the design, not an
 * oversight: to a caller, a deleted row is indistinguishable from one that never
 * existed.
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
            .where { ProjectTable.deletedAt.isNull() }
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
                .where(liveItemsOf(projectId) and matchOf(projectId, filter))
                .newestFirst(ProjectItemTable.createdAt, ProjectItemTable.id)
                .toList(),
        )
    }

    /**
     * The project's ready leaves, straight from the readiness view. No filter:
     * "what can be worked on" is one question, and narrowing it is what
     * [listItems] is for.
     *
     * The view decides which items are ready — deleted ones excluded, a deleted
     * blocker counted as resolved — and `project_item` supplies what each one
     * is, so the row-to-entity mapping stays in one place.
     */
    fun getReadyItems(projectRef: String): List<ProjectItem> = readTransaction(db) {
        val projectId = resolveProject(projectRef)[ProjectTable.id]
        val readyIds = ReadyItemView.select(ReadyItemView.id)
            .where { ReadyItemView.projectId eq projectId }
        withBlockerSets(
            ProjectItemTable.selectAll()
                .where(ProjectItemTable.id inSubQuery readyIds)
                .newestFirst(ProjectItemTable.createdAt, ProjectItemTable.id)
                .toList(),
        )
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun liveItemsOf(projectId: Uuid): Op<Boolean> =
        (ProjectItemTable.projectId eq projectId) and ProjectItemTable.deletedAt.isNull()

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
                val codes = requireValues(types, "type").map { label ->
                    ItemType.fromLabel(label)?.code
                        ?: validationFailed(
                            "\"$label\" is not an item type; the item types are ${ItemType.entries.joinToString { it.label }}",
                        )
                }
                add(ProjectItemTable.type inList codes)
            }
            filter.statuses?.let { statuses ->
                val codes = requireValues(statuses, "status").map { label ->
                    ItemStatus.fromLabel(label)?.code
                        ?: validationFailed(
                            "\"$label\" is not an item status; the item statuses are ${ItemStatus.entries.joinToString { it.label }}",
                        )
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
        }
        return parts.reduceOrNull { narrowed, part -> narrowed and part } ?: Op.TRUE
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
