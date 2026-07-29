@file:UseSerializers(InstantSerializer::class, LocalDateSerializer::class)

package io.nook.contract

import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

// The entities the core service returns from its operations, in-process and
// across the connection alike — one shape, so that what a caller receives over
// the wire is what the core produced rather than a rendering of it.
//
// A timestamp is an [Instant] — a moment, carrying no zone. The store keeps the
// zone so the moment survives being written on one machine and read on another;
// what a caller receives is the moment alone, because the offset a row happened
// to be written at means nothing to anyone reading it.
//
// The two time types are the only ones here the serialization library cannot
// write a conversion for; both are named at the top of this file, so a field
// added later is carried without anyone remembering to say so.
//
// Every entity names who made it and who last changed it, as a pair: the person
// the call was made for, and the coding agent that made it on their behalf. The
// agent is empty text wherever a person acted directly, which is every call on
// the web API — empty rather than a repeat of the person's own name, because
// no agent acted. None of the five is a field a caller may supply; each is
// settled by the token a call presented and by what a connection announced.

@Serializable
public data class Project(
    public val id: Uuid,
    public val slug: String,
    public val name: String,
    public val description: String?,
    public val artifactRepoUrl: String?,
    public val createdAt: Instant,
    public val updatedAt: Instant,
    public val createdBy: String,
    public val updatedBy: String,
    public val createdByAgent: String,
    public val updatedByAgent: String,
    /** Whose project this is, as against who wrote its row: set when it is created, and never afterwards. */
    public val ownerSubject: String,
)

/**
 * One work item of any type. Epics never have a parent and may be assigned to
 * a release; leaves may be parented under an epic and may carry blockers.
 * [blockedBy] holds the ids of the items this item is blocked by.
 */
@Serializable
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
    public val createdBy: String,
    public val updatedBy: String,
    public val createdByAgent: String,
    public val updatedByAgent: String,
)

@Serializable
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
    public val createdBy: String,
    public val updatedBy: String,
    public val createdByAgent: String,
    public val updatedByAgent: String,
)
