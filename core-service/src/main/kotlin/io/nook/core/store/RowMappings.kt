package io.nook.core.store

import io.nook.contract.ItemStatus
import io.nook.contract.ItemType
import io.nook.contract.Project
import io.nook.contract.ProjectItem
import io.nook.contract.Release
import io.nook.contract.ReleaseStatus
import io.nook.core.db.ItemDependencyTable
import io.nook.core.db.ProjectItemTable
import io.nook.core.db.ProjectTable
import io.nook.core.db.ReleaseTable
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll

// Rows to contract entities, shared by both paths: a read returns exactly what
// the write that produced it returned. Who wrote a row comes out with it,
// unaltered — the store holds exactly the two names an adapter told the core, and
// what a caller reads back is those. An unknown stored code is a corrupted
// store, not a caller error — this service is the single writer and never
// stores one — so it fails loudly instead of mapping to anything.
//
// Timestamps drop their offset on the way out. The store keeps one so that a
// row names an absolute moment rather than a wall clock, and the contract
// carries the moment alone; the offset a row was written at is a storage
// detail, and returning it would invite callers to read meaning into it.

internal fun ResultRow.toProject(): Project = Project(
    id = this[ProjectTable.id],
    slug = this[ProjectTable.slug],
    name = this[ProjectTable.name],
    description = this[ProjectTable.description],
    artifactRepoUrl = this[ProjectTable.artifactRepoUrl],
    createdAt = this[ProjectTable.createdAt].toInstant(),
    updatedAt = this[ProjectTable.updatedAt].toInstant(),
    createdBy = this[ProjectTable.createdBy],
    updatedBy = this[ProjectTable.updatedBy],
    createdByAgent = this[ProjectTable.createdByAgent],
    updatedByAgent = this[ProjectTable.updatedByAgent],
    ownerSubject = this[ProjectTable.ownerSubject],
)

internal fun ResultRow.toProjectItem(blockedBy: Set<Uuid>): ProjectItem = ProjectItem(
    id = this[ProjectItemTable.id],
    projectId = this[ProjectItemTable.projectId],
    parentId = this[ProjectItemTable.parentId],
    releaseId = this[ProjectItemTable.releaseId],
    type = ItemType.fromCode(this[ProjectItemTable.type])
        ?: error("the store holds item type code ${this[ProjectItemTable.type]}, which no member carries"),
    slug = this[ProjectItemTable.slug],
    name = this[ProjectItemTable.name],
    description = this[ProjectItemTable.description],
    status = ItemStatus.fromCode(this[ProjectItemTable.status])
        ?: error("the store holds item status code ${this[ProjectItemTable.status]}, which no member carries"),
    blockedBy = blockedBy,
    createdAt = this[ProjectItemTable.createdAt].toInstant(),
    updatedAt = this[ProjectItemTable.updatedAt].toInstant(),
    createdBy = this[ProjectItemTable.createdBy],
    updatedBy = this[ProjectItemTable.updatedBy],
    createdByAgent = this[ProjectItemTable.createdByAgent],
    updatedByAgent = this[ProjectItemTable.updatedByAgent],
)

internal fun ResultRow.toRelease(): Release = Release(
    id = this[ReleaseTable.id],
    projectId = this[ReleaseTable.projectId],
    slug = this[ReleaseTable.slug],
    name = this[ReleaseTable.name],
    description = this[ReleaseTable.description],
    status = ReleaseStatus.fromCode(this[ReleaseTable.status])
        ?: error("the store holds release status code ${this[ReleaseTable.status]}, which no member carries"),
    targetDate = this[ReleaseTable.targetDate],
    createdAt = this[ReleaseTable.createdAt].toInstant(),
    updatedAt = this[ReleaseTable.updatedAt].toInstant(),
    createdBy = this[ReleaseTable.createdBy],
    updatedBy = this[ReleaseTable.updatedBy],
    createdByAgent = this[ReleaseTable.createdByAgent],
    updatedByAgent = this[ReleaseTable.updatedByAgent],
)

/** The ids of the items [itemId] is blocked by. Must run inside an open transaction. */
internal fun blockersOf(itemId: Uuid): Set<Uuid> =
    ItemDependencyTable.selectAll()
        .where { ItemDependencyTable.itemId eq itemId }
        .map { it[ItemDependencyTable.dependsOnId] }
        .toSet()

/**
 * The blocker sets of many items at once, keyed by the blocked item and
 * omitting items with none. One query for the whole set rather than one per
 * item: measurably faster, and it keeps a listing's answer inside the two
 * statements one transaction makes consistent with each other.
 */
internal fun blockerSetsOf(itemIds: Collection<Uuid>): Map<Uuid, Set<Uuid>> {
    if (itemIds.isEmpty()) return emptyMap()
    return itemIds.chunked(MAX_IDS_PER_STATEMENT)
        .flatMap { chunk ->
            ItemDependencyTable.selectAll()
                .where { ItemDependencyTable.itemId inList chunk }
                .map { it[ItemDependencyTable.itemId] to it[ItemDependencyTable.dependsOnId] }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, blockers) -> blockers.toSet() }
}

/**
 * How many ids one statement may name. The limit is the wire protocol's: a
 * statement carries at most 65535 bound parameters, and one id is one parameter,
 * so a listing of a large enough project would fail on its blocker sets alone —
 * at a size nothing about the query looks wrong at. Well under it, and large
 * enough that ordinary listings still take one statement.
 */
private const val MAX_IDS_PER_STATEMENT = 10_000
