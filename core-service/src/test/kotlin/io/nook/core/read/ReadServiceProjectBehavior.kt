package io.nook.core.read

import io.nook.contract.CreateProject
import io.nook.contract.ErrorCode
import io.nook.contract.StructuredErrorException
import io.nook.core.catalog.CatalogBehavior
import io.nook.core.db.ProjectTable
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Reading projects: the one pair of operations a caller reaches before it has
 * bound itself to any project at all.
 *
 * The checks about a listing take an instance of their own rather than a
 * project of their own, because a project listing is instance-wide: on a shared
 * store another test's project would show up in the answer.
 */
abstract class ReadServiceProjectBehavior : CatalogBehavior() {

    @Test
    fun `the listing shows the live projects newest first, and the deleted one nowhere`() {
        val service = ownInstance().service
        val first = service.createProject(CreateProject("First"))
        val second = service.createProject(CreateProject("Second"))
        val third = service.createProject(CreateProject("Third"))
        val gone = service.createProject(CreateProject("Gone"))
        service.deleteProject(gone.slug)

        val listed = service.listProjects()

        assertEquals(listOf(third.id, second.id, first.id), listed.map { it.id })
    }

    @Test
    fun `a project is fetched by its handle without any project being bound first`() {
        val project = service.createProject(CreateProject("Fetched By Handle"))

        assertEquals(project, service.getProject("fetched-by-handle"))
        assertEquals(project, service.getProject(project.id.toString()))
    }

    @Test
    fun `a deleted project is not found by its id or by its handle`() {
        val gone = service.createProject(CreateProject("Deleted Project"))
        service.deleteProject(gone.slug)

        val byId = assertFailsWith<StructuredErrorException> { service.getProject(gone.id.toString()) }
        assertEquals(ErrorCode.NOT_FOUND, byId.error.code)
        val bySlug = assertFailsWith<StructuredErrorException> { service.getProject(gone.slug) }
        assertEquals(ErrorCode.NOT_FOUND, bySlug.error.code)
    }

    @Test
    fun `an instance holding no projects lists an empty array rather than failing`() {
        val service = ownInstance().service
        assertEquals(emptyList(), service.listProjects())
    }

    @Test
    fun `an identical project listing returns an identical order, same-instant rows included`() {
        val (db, service) = ownInstance()
        val inSequence = (1..3).map { service.createProject(CreateProject("Sequential $it")) }
        // Two projects sharing one creation instant, which no pair of separate
        // write transactions would produce: without the id breaking their tie,
        // the order between them is the query plan's to choose.
        val sharedInstant = Instant.now().plusSeconds(60)
        val twins = (1..2).map { number ->
            val id = Uuid.random()
            transaction(db) {
                ProjectTable.insert {
                    it[ProjectTable.id] = id
                    it[ProjectTable.slug] = "twin-$number"
                    it[ProjectTable.name] = "Twin $number"
                    it[ProjectTable.createdAt] = sharedInstant.atOffset(ZoneOffset.UTC)
                    it[ProjectTable.updatedAt] = sharedInstant.atOffset(ZoneOffset.UTC)
                }
            }
            id to sharedInstant
        }

        val orders = (1..10).map { service.listProjects().map { project -> project.id } }

        val expected = (twins + inSequence.map { it.id to it.createdAt })
            .sortedWith(compareByDescending<Pair<Uuid, Instant>> { it.second }.thenBy { it.first.toString() })
            .map { it.first }
        orders.forEach { order ->
            assertEquals(expected, order, "every identical call must return the same order")
        }
    }
}

class ReadServiceProjectInProcessTest : ReadServiceProjectBehavior() {
    override val reach = Reach.IN_PROCESS
}

class ReadServiceProjectAcrossConnectionTest : ReadServiceProjectBehavior() {
    override val reach = Reach.ACROSS_THE_CONNECTION
}
