package io.nook.core.read

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.ItemFilter
import io.nook.contract.SetItemBlockedBy
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.ProjectItemTable
import io.nook.core.store.blockerSetsOf
import io.nook.core.write.WriteService
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * A listing is assembled from two statements — the items, then their blocker
 * sets — so it could describe a moment that never existed: an item read before
 * somebody else's commit, its blockers read after. The read path's transaction
 * reads one moment for exactly this reason, and this is where that claim is
 * put under a writer working at the same time.
 *
 * Each round commits three writes, in the order that makes the anomaly
 * reachable: the leaf first, then the item that blocks it, then the edge. A
 * reader lists throughout. Between the reader's first statement and its second,
 * a blocker can appear and be pointed at — so at PostgreSQL's normal setting the
 * blocker sets could name an item the listing itself never showed. Reading one
 * moment is what rules that out, and what every listing here is checked against.
 */
class ReadServiceConcurrencyTest {

    private companion object {
        const val ROUNDS = 100
    }

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
    private fun blockersNamedButNeverShown(isolation: Int, projectName: String): Set<Uuid> {
        val project = writes.createProject(CreateProject(projectName))
        writes.createItem(project.slug, CreateItem(type = "task", name = "Waiting leaf"))

        return transaction(db, isolation, readOnly = true) {
            val shown = ProjectItemTable.selectAll()
                .where { (ProjectItemTable.projectId eq project.id) and ProjectItemTable.deletedAt.isNull() }
                .map { it[ProjectItemTable.id] }

            val other = Thread {
                writes.createItem(project.slug, CreateItem(type = "task", name = "Late blocker"))
                writes.setItemBlockedBy(
                    project.slug, "waiting-leaf",
                    SetItemBlockedBy(listOf("late-blocker")),
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
            Connection.TRANSACTION_READ_COMMITTED,
            "Two Moments",
        )

        assertTrue(
            straddled.isNotEmpty(),
            "the anomaly this discipline prevents must be reachable, or the discipline proves nothing",
        )
    }

    @Test
    fun `reading one moment leaves the two statements agreeing`() {
        val straddled = blockersNamedButNeverShown(
            Connection.TRANSACTION_REPEATABLE_READ,
            "One Moment",
        )

        assertEquals(emptySet(), straddled)
    }

    @Test
    fun `a listing taken while another caller writes always shows fully committed items`() {
        val reads = ReadService(db)
        val project = writes.createProject(CreateProject("Listed While Written"))

        val started = CountDownLatch(1)
        val failures = mutableListOf<Throwable>()
        val writer = Thread {
            started.countDown()
            repeat(ROUNDS) { round ->
                runCatching {
                    writes.createItem(project.slug, CreateItem(type = "task", name = "Blocked $round"))
                    writes.createItem(project.slug, CreateItem(type = "task", name = "Blocker $round"))
                    writes.setItemBlockedBy(
                        project.slug, "blocked-$round",
                        SetItemBlockedBy(listOf("blocker-$round")),
                    )
                }.onFailure { synchronized(failures) { failures += it } }
            }
        }

        writer.start()
        started.await()
        val listings = buildList {
            repeat(ROUNDS) {
                add(runCatching { reads.listItems(project.slug, ItemFilter()) })
            }
        }
        writer.join()

        assertEquals(emptyList(), failures.toList(), "no write may fail")
        listings.forEachIndexed { attempt, listing ->
            val items = listing.getOrElse { throw AssertionError("listing $attempt failed", it) }
            val visible = items.map { it.id }.toSet()
            items.forEach { item ->
                val unseen = item.blockedBy - visible
                assertTrue(
                    unseen.isEmpty(),
                    "listing $attempt gave ${item.slug} a blocker it never showed: $unseen",
                )
                val expected = "blocker-${item.slug.substringAfter('-')}"
                assertTrue(
                    item.blockedBy.size <= 1 &&
                        item.blockedBy.all { blockerId -> items.single { it.id == blockerId }.slug == expected },
                    "listing $attempt gave ${item.slug} a blocker set it was never written with",
                )
            }
        }
        assertEquals(2 * ROUNDS, reads.listItems(project.slug, ItemFilter()).size)
    }
}
