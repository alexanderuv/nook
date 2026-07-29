package io.nook.core.catalog

import io.nook.contract.Actor
import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.CreateRelease
import io.nook.contract.ItemFilter
import io.nook.contract.OperationCatalog
import io.nook.contract.Project
import io.nook.contract.ProjectItem
import io.nook.contract.Release
import io.nook.contract.UpdateItem
import io.nook.contract.UpdateRelease
import io.nook.core.read.ReadService
import io.nook.core.write.WriteService
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * The catalog as the core itself answers it: the seven mutations and the four
 * reads, each handed straight to the service that owns it.
 *
 * Nothing is decided here. The two services keep the whole of what an operation
 * does — its validation, its locking, its transaction — and this adds no rule of
 * its own, which is what lets the same behavior suite run against this and
 * against the calling library and expect one verdict from both.
 *
 * [actor] is who the calls made through this instance are for, and it is handed
 * to the write path as a parameter on every mutation rather than held anywhere
 * that outlives one. The four reads are handed nothing: a read records nobody,
 * and one that named a person would be offering a rule about who may read.
 *
 * Built without one, it names nobody — every mutation through it is refused, and
 * every read is served. That is what an adapter with a defect gets, and it is
 * deliberately not a row crediting nobody.
 */
class CoreCatalog(
    private val writes: WriteService,
    private val reads: ReadService,
    private val actor: Actor = Actor.NOBODY,
) : OperationCatalog {

    constructor(db: Database) : this(WriteService(db), ReadService(db))

    override fun forActor(actor: Actor): OperationCatalog = CoreCatalog(writes, reads, actor)

    override fun createProject(command: CreateProject): Project = writes.createProject(actor, command)

    override fun getProject(ref: String): Project = reads.getProject(ref)

    override fun listProjects(): List<Project> = reads.listProjects()

    override fun deleteProject(ref: String) = writes.deleteProject(actor, ref)

    override fun createItem(projectRef: String, command: CreateItem): ProjectItem =
        writes.createItem(actor, projectRef, command)

    override fun updateItem(projectRef: String, itemRef: String, command: UpdateItem): ProjectItem =
        writes.updateItem(actor, projectRef, itemRef, command)

    override fun deleteItem(projectRef: String, itemRef: String) = writes.deleteItem(actor, projectRef, itemRef)

    override fun createRelease(projectRef: String, command: CreateRelease): Release =
        writes.createRelease(actor, projectRef, command)

    override fun updateRelease(projectRef: String, releaseRef: String, command: UpdateRelease): Release =
        writes.updateRelease(actor, projectRef, releaseRef, command)

    override fun getItem(projectRef: String, itemRef: String): ProjectItem = reads.getItem(projectRef, itemRef)

    override fun listItems(projectRef: String, filter: ItemFilter): List<ProjectItem> =
        reads.listItems(projectRef, filter)
}
