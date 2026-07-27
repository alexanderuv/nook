package io.nook.core.read

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.FieldChange
import io.nook.contract.UpdateItem
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.ProjectItemTable
import io.nook.core.store.blockerSetsOf
import io.nook.core.write.WriteService
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * A listing is assembled from two statements — the items, then their blocker
 * sets — so it could describe a moment that never existed: an item read before
 * somebody else's commit, its blockers read after. The read path's transaction
 * reads one moment for exactly this reason, and this is where that claim is
 * put under a writer working at the same time.
 *
 * Between the reader's first statement and its second, a blocker can appear and
 * be pointed at — so at PostgreSQL's normal setting the blocker sets could name
 * an item the listing itself never showed. Reading one moment is what rules that
 * out.
 *
 * Both checks drive that transaction directly rather than calling an operation,
 * which is why they stay in the core's own process: what they are about is a
 * discipline inside the read path, and the connection has no view of it. The
 * same claim as a caller meets it — a listing taken while another caller writes
 * — is asked both ways, in its own suite.
 */
class ReadServiceConcurrencyTest {

    private val db = Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase())
    private val writes = WriteService(db)

    /**
     * Runs a listing's two statements by hand with another caller committing
     * between them, and returns the blockers the answer names but never showed.
     * Empty means the two statements described one moment.
     *
     * The write is deliberately the shape that makes the anomaly reachable: a
     * blocker that did not exist when the first statement ran, pointed at by an
     * edge that does exist when the second one does.
     */
    private fun blockersNamedButNeverShown(
        reader: (JdbcTransaction.() -> Set<Uuid>) -> Set<Uuid>,
        projectName: String,
    ): Set<Uuid> {
        val project = writes.createProject(CreateProject(projectName))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Waiting leaf"))

        return reader {
            val shown = ProjectItemTable.selectAll()
                .where { ProjectItemTable.projectId eq project.id }
                .map { it[ProjectItemTable.id] }

            val other = Thread {
                writes.createItem(project.slug, CreateItem(type = "task", name = "Late blocker"))
                writes.updateItem(
                    project.slug, "waiting-leaf",
                    UpdateItem(blockedBy = FieldChange.Set(listOf("late-blocker"))),
                )
            }
            other.start()
            other.join()

            blockerSetsOf(shown).values.flatten().toSet() - shown.toSet()
        }
    }

    @Test
    fun `at the database's normal setting the two statements can describe two moments`() {
        val straddled = blockersNamedButNeverShown(
            { block -> transaction(db, Connection.TRANSACTION_READ_COMMITTED, readOnly = true, statement = block) },
            "Two Moments",
        )

        assertTrue(
            straddled.isNotEmpty(),
            "the anomaly this discipline prevents must be reachable, or the discipline proves nothing",
        )
    }

    /**
     * Through [readTransaction] itself, not through a transaction configured to
     * match it. Handing the isolation level in as a parameter would test
     * PostgreSQL's repeatable read and say nothing about whether the read path
     * asks for it: the level could be changed in [readTransaction] and this test
     * would stay green.
     */
    @Test
    fun `reading one moment leaves the two statements agreeing`() {
        val straddled = blockersNamedButNeverShown(
            { block -> readTransaction(db, block) },
            "One Moment",
        )

        assertEquals(emptySet(), straddled)
    }

}
