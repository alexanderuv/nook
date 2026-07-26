package io.nook.core.write

import io.nook.contract.CreateProject
import io.nook.contract.ErrorCode
import io.nook.contract.StructuredErrorException
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.InstanceLockTable
import io.nook.core.db.ProjectItemTable
import io.nook.core.db.ProjectTable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Writers in one scope take turns, and the turn is taken by locking a row —
 * plain standard SQL, no engine-specific lock primitive.
 *
 * Two scopes exist. A project's own row serves writers inside it. The
 * instance-wide space of project handles has no row of its own, so a dedicated
 * one is locked instead; that row must exist, and its absence is a corrupted
 * schema rather than a licence to skip the turn.
 *
 * Every wait here is bounded and every worker is a daemon. A test about threads
 * blocking each other is exactly the one that hangs when the thing it tests
 * breaks, and a hang is the worst failure to receive: no assertion message, no
 * report, and a build that runs until something outside it gives up.
 */
class WriteLockTest {

    private companion object {
        /**
         * Long enough that no loaded machine trips it, short enough that a
         * regression fails the test rather than wedging the run. Every wait here
         * is for another thread that either arrives in milliseconds or never.
         */
        const val WAIT_SECONDS = 30L

        /** How often the database is asked whether a session is blocked yet. */
        const val POLL_MILLIS = 20L
    }

    private fun CountDownLatch.awaitOrFail(what: String) {
        if (!await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw AssertionError("timed out after ${WAIT_SECONDS}s waiting for $what")
        }
    }

    private fun Thread.joinOrFail(what: String) {
        join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        if (isAlive) throw AssertionError("$what never finished within ${WAIT_SECONDS}s")
    }

    /** The number of sessions the database currently reports as waiting on a lock. */
    private fun sessionsWaitingOnLock(db: Database): Int = transaction(db) {
        exec("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'") { rows ->
            rows.next()
            rows.getInt(1)
        } ?: 0
    }

    /**
     * Blocks until the database itself reports a session waiting on a lock, and
     * fails if [acquired] flips first.
     *
     * Sleeping a fixed interval and then asserting that nothing happened would
     * prove nothing: on a busy machine the second writer may not have reached
     * the lock at all, and the assertion passes for a reason unrelated to
     * locking. Asking the database which session is blocked is the difference
     * between "has not arrived yet" and "arrived and is waiting its turn".
     */
    private fun awaitBlockedOnLock(db: Database, acquired: AtomicBoolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            assertFalse(acquired.get(), "a second writer took the lock while the first still held it")
            if (sessionsWaitingOnLock(db) > 0) return
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("no session ever blocked on the lock within ${WAIT_SECONDS}s")
    }

    @Test
    fun `a second writer waits for the project row and then sees the first writer's commit`() {
        val db = Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase())
        val projectId = Uuid.random()
        transaction(db) {
            ProjectTable.insert {
                it[id] = projectId
                it[slug] = "locked-project"
                it[name] = "Locked project"
            }
        }

        val firstHoldsLock = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondAcquired = AtomicBoolean(false)
        val itemsSeenBySecond = AtomicInteger(-1)

        val first = thread(isDaemon = true) {
            writeTransaction(db) {
                lockProject("locked-project")
                ProjectItemTable.insert {
                    it[id] = Uuid.random()
                    it[ProjectItemTable.projectId] = projectId
                    it[type] = 2
                    it[slug] = "first-item"
                    it[name] = "First item"
                }
                firstHoldsLock.countDown()
                releaseFirst.awaitOrFail("the test to release the first writer")
            }
        }
        val second = thread(isDaemon = true) {
            firstHoldsLock.awaitOrFail("the first writer to take the lock")
            writeTransaction(db) {
                lockProject("locked-project")
                secondAcquired.set(true)
                itemsSeenBySecond.set(
                    ProjectItemTable.selectAll()
                        .where { ProjectItemTable.projectId eq projectId }
                        .count()
                        .toInt(),
                )
            }
        }

        awaitBlockedOnLock(db, secondAcquired)

        releaseFirst.countDown()
        first.joinOrFail("the first writer")
        second.joinOrFail("the second writer")
        assertTrue(secondAcquired.get(), "the second writer never acquired the lock")
        assertEquals(
            1,
            itemsSeenBySecond.get(),
            "the second writer must see the first writer's committed item",
        )
    }

    @Test
    fun `a second writer waits for the instance lock row`() {
        val db = Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase())

        val firstHoldsLock = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondAcquired = AtomicBoolean(false)

        val first = thread(isDaemon = true) {
            writeTransaction(db) {
                takeInstanceLock()
                firstHoldsLock.countDown()
                releaseFirst.awaitOrFail("the test to release the first writer")
            }
        }
        val second = thread(isDaemon = true) {
            firstHoldsLock.awaitOrFail("the first writer to take the instance lock")
            writeTransaction(db) {
                takeInstanceLock()
                secondAcquired.set(true)
            }
        }

        awaitBlockedOnLock(db, secondAcquired)

        releaseFirst.countDown()
        first.joinOrFail("the first writer")
        second.joinOrFail("the second writer")
        assertTrue(secondAcquired.get(), "the second writer never acquired the instance lock")
    }

    @Test
    fun `locking a project that is not there is not found rather than a silent pass`() {
        val db = Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase())

        val failure = assertFailsWith<StructuredErrorException> {
            writeTransaction(db) { lockProject("no-such-project") }
        }
        assertEquals(ErrorCode.NOT_FOUND, failure.error.code)
    }

    @Test
    fun `a missing instance lock row is reported as a broken schema, never skipped`() {
        val db = Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase())
        transaction(db) { InstanceLockTable.deleteWhere { InstanceLockTable.scope eq "project_slug" } }

        // Carrying on without the row would forfeit the turn-taking that project
        // handles depend on, and nothing would say so.
        val failure = assertFailsWith<IllegalStateException> {
            writeTransaction(db) { takeInstanceLock() }
        }
        assertTrue(
            failure.message.orEmpty().contains("lock row"),
            "expected the missing row to be named, got: ${failure.message}",
        )
        assertFailsWith<IllegalStateException> {
            WriteService(db).createProject(CreateProject("Unprotected"))
        }
    }
}
