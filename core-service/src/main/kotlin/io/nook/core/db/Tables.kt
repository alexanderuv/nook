package io.nook.core.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// The structure schema, declared for Exposed. The database itself is built and
// evolved exclusively by the Liquibase changelog; these declarations must mirror
// it exactly — every column type, nullability, default, constraint, and index.
//
// Two checks hold them to it, because one of them alone would not. Exposed's own
// comparison reports what the migrated database is missing, which catches a
// declaration with no counterpart; it does not read what each rule *says*, so a
// CHECK rewritten under its own name or a column retyped between two types it
// treats as equivalent passes it silently. The other check builds the schema a
// second time from these declarations onto an empty database and diffs what
// PostgreSQL reports about the two — indexes, columns, and constraint
// definitions — which is the reading rather than a summary of it.
//
// Enum-valued columns (type, status, kind) are SMALLINT codes; the meaning lives
// in application enums whose members carry explicit, stable integers. The single
// writer (this service) enforces domain membership, containment, and per-type
// rules — the schema stores no such semantics beyond its constraints.
//
// Every row that records who wrote it records both halves of the pair: the
// person the call was made for (created_by / updated_by) and the coding agent
// that made it on their behalf (created_by_agent / updated_by_agent). The agent
// is empty text where a person acted directly, which is not the same as unknown
// — no agent acted. `item_dependency` records neither, by having no audit
// column at all, and `document` gains the agent pair when the document layer
// starts writing it.
//
// Audit timestamps are TIMESTAMPTZ, carried here as OffsetDateTime and handed to
// callers as the Instant the contract promises. A zoneless column would store
// the writing machine's wall clock and rebuild an Instant from the reading
// machine's zone, so the same row would name a different moment on a different
// host. The drift check cannot tell the two column types apart, which is why the
// mismatch has to be prevented rather than caught.

// The rows writers lock to take a turn in a scope that owns no row of its own —
// today just the instance-wide space of project handles. Holds no data: the row
// exists to be locked FOR UPDATE and nothing reads its contents. One row per
// scope, so a second scope would not queue behind this one.
object InstanceLockTable : Table("instance_lock") {
    val scope = varchar("scope", 100)

    override val primaryKey = PrimaryKey(scope)
}

object ProjectTable : Table("project") {
    val id = uuid("id")
    val slug = varchar("slug", 200).uniqueIndex("uq_project_slug")
    val name = varchar("name", 500)
    val description = text("description").default("").nullable()
    val artifactRepoUrl = varchar("artifact_repo_url", 1024).nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
    val createdBy = varchar("created_by", 200).default("system")
    val updatedBy = varchar("updated_by", 200).default("system")
    val createdByAgent = varchar("created_by_agent", 200).default("")
    val updatedByAgent = varchar("updated_by_agent", 200).default("")

    // The tenancy root: the subject that owns this project (distinct from
    // created_by, which is audit). Single-valued — one owner per project.
    val ownerSubject = varchar("owner_subject", 200).default("system")

    override val primaryKey = PrimaryKey(id)
}

object ReleaseTable : Table("release") {
    val id = uuid("id")
    val projectId = uuid("project_id")
        .references(ProjectTable.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "fk_release_project")
    val slug = varchar("slug", 200)
    val name = varchar("name", 500)
    val description = text("description").default("").nullable()
    val status = short("status").default(1)
    val targetDate = date("target_date").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
    val createdBy = varchar("created_by", 200).default("system")
    val updatedBy = varchar("updated_by", 200).default("system")
    val createdByAgent = varchar("created_by_agent", 200).default("")
    val updatedByAgent = varchar("updated_by_agent", 200).default("")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_release_project_slug", projectId, slug)
        // Referenced by project_item's composite FK (same-project membership).
        uniqueIndex("uq_release_project_id", projectId, id)
    }
}

// One entity for all work items (epic / task / bug / chore), discriminated by
// `type`. parent_id places a leaf under an epic and is NULL for epics and
// project-level leaves; release_id applies to epics. "Only an epic parents" and
// "leaves never nest" are write-path rules — the type column carries the meaning.
object ProjectItemTable : Table("project_item") {
    val id = uuid("id")
    val projectId = uuid("project_id")
        .references(ProjectTable.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "fk_item_project")
    val parentId = uuid("parent_id").nullable()
    val releaseId = uuid("release_id").nullable()
    val type = short("type")
    val slug = varchar("slug", 200)
    val name = varchar("name", 500)
    val description = text("description").default("").nullable()
    val status = short("status").default(1)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
    val createdBy = varchar("created_by", 200).default("system")
    val updatedBy = varchar("updated_by", 200).default("system")
    val createdByAgent = varchar("created_by_agent", 200).default("")
    val updatedByAgent = varchar("updated_by_agent", 200).default("")

    override val primaryKey = PrimaryKey(id)

    init {
        // Slug unique per project across ALL item types — a slug reference must
        // resolve to exactly one item.
        uniqueIndex("uq_item_project_slug", projectId, slug)
        // Referenced by the parent self-FK and by document's composite item FK.
        uniqueIndex("uq_item_project_id", projectId, id)
        // A leaf's parent (an epic) must be in the SAME project. NO ACTION keeps
        // project → project_item the only cascade path.
        foreignKey(
            projectId to projectId, parentId to id,
            onDelete = ReferenceOption.NO_ACTION,
            onUpdate = ReferenceOption.NO_ACTION,
            name = "fk_item_parent_same_project",
        )
        // An epic's release must be in the SAME project.
        foreignKey(
            projectId to ReleaseTable.projectId, releaseId to ReleaseTable.id,
            onDelete = ReferenceOption.SET_NULL,
            onUpdate = ReferenceOption.NO_ACTION,
            name = "fk_item_release_same_project",
        )
        index("ix_item_project_type_status", false, projectId, type, status)
        index("ix_item_parent_status", false, parentId, status)
    }
}

// blocked_by edges between leaves. Same-project and leaves-only are write-path
// rules; self-blocking is rejected structurally.
object ItemDependencyTable : Table("item_dependency") {
    val itemId = uuid("item_id")
        .references(ProjectItemTable.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "fk_dep_item")
    val dependsOnId = uuid("depends_on_id")
        .references(ProjectItemTable.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "fk_dep_blocker")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(itemId, dependsOnId, name = "pk_item_dependency")

    init {
        index("ix_dep_depends_on", false, dependsOnId)
        check("ck_dep_no_self_block") { itemId neq dependsOnId }
    }
}

// A document is a pointer: content lives in git, these rows hold structure plus
// path + current git version. Always project-scoped; item_id optionally attaches
// it to one same-project item and is NULL for project-level documents. Per-kind
// level rules (which kinds require or forbid item_id, singletons) are write-path
// rules.
object DocumentTable : Table("document") {
    val id = uuid("id")
    val projectId = uuid("project_id")
        .references(ProjectTable.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "fk_doc_project")
    val itemId = uuid("item_id").nullable()
    val kind = short("kind")

    // Per-(project, kind) citation number for numbered kinds; NULL for the
    // unnumbered ones. Allocated from document_sequence, never reused.
    // Uniqueness is write-path policy: a UNIQUE over a nullable column is not
    // portable, and the single writer is the sole allocator anyway.
    val seq = integer("seq").nullable()
    val name = varchar("name", 300)
    val title = varchar("title", 500).nullable()

    // The entity-scoped name; its global uniqueness is what makes two documents
    // with the same name under the same item collide, while the same name may
    // repeat across items.
    val path = varchar("path", 1024).uniqueIndex("uq_document_path")
    val currentVersion = varchar("current_version", 64).nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
    val createdBy = varchar("created_by", 200).default("system")
    val updatedBy = varchar("updated_by", 200).default("system")

    override val primaryKey = PrimaryKey(id)

    init {
        // When item_id is set, (project_id, item_id) must be a real same-project
        // item. NO ACTION so project → document stays the single cascade path.
        foreignKey(
            projectId to ProjectItemTable.projectId, itemId to ProjectItemTable.id,
            onDelete = ReferenceOption.NO_ACTION,
            onUpdate = ReferenceOption.NO_ACTION,
            name = "fk_doc_item_same_project",
        )
        // Both ways a document is reached by what owns it: the cascade from
        // project, and the write path taking an item's documents with it. A
        // leading-column prefix serves the first, the whole index the second.
        index("ix_doc_project_item", false, projectId, itemId)
    }
}

// High-water counter behind document.seq: a row per (project, kind), created
// lazily on first numbered document. next_seq only increases, so numbers are
// never reused even after the newest document of a kind is deleted.
object DocumentSequenceTable : Table("document_sequence") {
    val projectId = uuid("project_id")
        .references(ProjectTable.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "fk_docseq_project")
    val kind = short("kind")
    val nextSeq = integer("next_seq").default(1)

    override val primaryKey = PrimaryKey(projectId, kind, name = "pk_document_sequence")
}

/** Every table of the structure schema, in dependency order — the drift check's scope. */
val allStructureTables = arrayOf(
    InstanceLockTable,
    ProjectTable,
    ReleaseTable,
    ProjectItemTable,
    ItemDependencyTable,
    DocumentTable,
    DocumentSequenceTable,
)
