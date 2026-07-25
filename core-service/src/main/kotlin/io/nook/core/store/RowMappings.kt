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
// the write that produced it returned. An unknown stored code is a corrupted
// store, not a caller error — this service is the single writer and never
// stores one — so it fails loudly instead of mapping to anything.

internal fun ResultRow.toProject(): Project = Project(
    id = this[ProjectTable.id],
    slug = this[ProjectTable.slug],
    name = this[ProjectTable.name],
    description = this[ProjectTable.description],
    artifactRepoUrl = this[ProjectTable.artifactRepoUrl],
    createdAt = this[ProjectTable.createdAt],
    updatedAt = this[ProjectTable.updatedAt],
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
    createdAt = this[ProjectItemTable.createdAt],
    updatedAt = this[ProjectItemTable.updatedAt],
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
    createdAt = this[ReleaseTable.createdAt],
    updatedAt = this[ReleaseTable.updatedAt],
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
    return ItemDependencyTable.selectAll()
        .where { ItemDependencyTable.itemId inList itemIds }
        .groupBy({ it[ItemDependencyTable.itemId] }, { it[ItemDependencyTable.dependsOnId] })
        .mapValues { (_, blockers) -> blockers.toSet() }
}
