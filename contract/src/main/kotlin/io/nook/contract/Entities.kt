package io.nook.contract

import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid

// The entities the core service returns from its operations. Plain data
// classes: the wire format (serializers, transport shapes) is designed when a
// wire exists; until then these are in-process values only.

data class Project(
    val id: Uuid,
    val slug: String,
    val name: String,
    val description: String?,
    val artifactRepoUrl: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * One work item of any type. Epics never have a parent and may be assigned to
 * a release; leaves may be parented under an epic and may carry blockers.
 * [blockedBy] holds the ids of the items this item is blocked by.
 */
data class ProjectItem(
    val id: Uuid,
    val projectId: Uuid,
    val parentId: Uuid?,
    val releaseId: Uuid?,
    val type: ItemType,
    val slug: String,
    val name: String,
    val description: String?,
    val status: ItemStatus,
    val blockedBy: Set<Uuid>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Release(
    val id: Uuid,
    val projectId: Uuid,
    val slug: String,
    val name: String,
    val description: String?,
    val status: ReleaseStatus,
    val targetDate: LocalDate?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
