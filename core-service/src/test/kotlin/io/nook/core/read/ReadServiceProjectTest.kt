package io.nook.core.read

import io.nook.contract.CreateProject
import io.nook.contract.ErrorCode
import io.nook.contract.StructuredErrorException
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.ProjectTable
import io.nook.core.write.WriteService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Reading projects: the one pair of operations a caller reaches before it has
 * bound itself to any project at all.
 *
 * Each test gets its own instance rather than its own project, because a
 * project listing is instance-wide: on a shared database another test's project
 * would show up in the answer.
 */
class ReadServiceProjectTest {

    private val db = Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase())
    private val writes = WriteService(db)
    private val reads = ReadService(db)

    @Test
    fun `the listing shows the live projects newest first, and the deleted one nowhere`() {
        val first = writes.createProject(CreateProject("First"))
        val second = writes.createProject(CreateProject("Second"))
        val third = writes.createProject(CreateProject("Third"))
        val gone = writes.createProject(CreateProject("Gone"))
        writes.deleteProject(gone.slug)

        val listed = reads.listProjects()

        assertEquals(listOf(third.id, second.id, first.id), listed.map { it.id })
    }

    @Test
    fun `a project is fetched by its handle without any project being bound first`() {
        val project = writes.createProject(CreateProject("Fetched By Handle"))

        assertEquals(project, reads.getProject("fetched-by-handle"))
        assertEquals(project, reads.getProject(project.id.toString()))
    }

    @Test
    fun `a deleted project is not found by its id or by its handle`() {
        val gone = writes.createProject(CreateProject("Deleted Project"))
        writes.deleteProject(gone.slug)

        val byId = assertFailsWith<StructuredErrorException> { reads.getProject(gone.id.toString()) }
        assertEquals(ErrorCode.NOT_FOUND, byId.error.code)
        val bySlug = assertFailsWith<StructuredErrorException> { reads.getProject(gone.slug) }
        assertEquals(ErrorCode.NOT_FOUND, bySlug.error.code)
    }

    @Test
    fun `an instance holding no projects lists an empty array rather than failing`() {
        assertEquals(emptyList(), reads.listProjects())
    }

    @Test
    fun `an identical project listing returns an identical order, same-instant rows included`() {
        val inSequence = (1..3).map { writes.createProject(CreateProject("Sequential $it")) }
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
                    it[ProjectTable.createdAt] = sharedInstant
                    it[ProjectTable.updatedAt] = sharedInstant
                }
            }
            id to sharedInstant
        }

        val orders = (1..10).map { reads.listProjects().map { project -> project.id } }

        val expected = (twins + inSequence.map { it.id to it.createdAt })
            .sortedWith(compareByDescending<Pair<Uuid, Instant>> { it.second }.thenBy { it.first.toString() })
            .map { it.first }
        orders.forEach { order ->
            assertEquals(expected, order, "every identical call must return the same order")
        }
    }
}
