package io.nook.contract

import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid

// The entities the core service returns from its operations. Plain data
// classes: the wire format (serializers, transport shapes) is designed when a
// wire exists; until then these are in-process values only.
//
// A timestamp is an [Instant] — a moment, carrying no zone. The store keeps the
// zone so the moment survives being written on one machine and read on another;
// what a caller receives is the moment alone, because the offset a row happened
// to be written at means nothing to anyone reading it.

public data class Project(
    public val id: Uuid,
    public val slug: String,
    public val name: String,
    public val description: String?,
    public val artifactRepoUrl: String?,
    public val createdAt: Instant,
    public val updatedAt: Instant,
)

/**
 * One work item of any type. Epics never have a parent and may be assigned to
 * a release; leaves may be parented under an epic and may carry blockers.
 * [blockedBy] holds the ids of the items this item is blocked by.
 */
public data class ProjectItem(
    public val id: Uuid,
    public val projectId: Uuid,
    public val parentId: Uuid?,
    public val releaseId: Uuid?,
    public val type: ItemType,
    public val slug: String,
    public val name: String,
    public val description: String?,
    public val status: ItemStatus,
    public val blockedBy: Set<Uuid>,
    public val createdAt: Instant,
    public val updatedAt: Instant,
)

public data class Release(
    public val id: Uuid,
    public val projectId: Uuid,
    public val slug: String,
    public val name: String,
    public val description: String?,
    public val status: ReleaseStatus,
    public val targetDate: LocalDate?,
    public val createdAt: Instant,
    public val updatedAt: Instant,
)
