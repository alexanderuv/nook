package io.nook.core.write

import io.nook.contract.ProjectItem
import io.nook.core.db.ProjectItemTable
import io.nook.core.db.ProjectTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

// Test-side state inspection. The service's public surface deliberately has
// no reads, so assertions query the tables directly through the Exposed
// helpers — a plain read, no mutation, no timestamp touched.

internal fun readItem(db: Database, projectSlug: String, itemRef: String): ProjectItem =
    transaction(db) {
        val projectId = resolveProject(projectSlug)[ProjectTable.id]
        val row = resolveItem(projectId, itemRef)
        row.toProjectItem(blockersOf(row[ProjectItemTable.id]))
    }
