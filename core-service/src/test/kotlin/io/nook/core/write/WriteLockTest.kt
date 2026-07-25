package io.nook.core.write

import io.nook.contract.CreateProject
import io.nook.contract.ErrorCode
import io.nook.contract.StructuredErrorException
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.InstanceLockTable
import io.nook.core.db.ProjectItemTable
import io.nook.core.db.ProjectTable
import kotlin.uuid.Uuid
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
 */
class WriteLockTest {

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
        val secondAttempting = CountDownLatch(1)
        val secondAcquired = AtomicBoolean(false)
        val itemsSeenBySecond = AtomicInteger(-1)

        val first = thread {
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
                releaseFirst.await()
            }
        }
        val second = thread {
            firstHoldsLock.await()
            secondAttempting.countDown()
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

        secondAttempting.await()
        Thread.sleep(300)
        assertFalse(
            secondAcquired.get(),
            "the second writer acquired the lock while the first still held it",
        )

        releaseFirst.countDown()
        first.join(10_000)
        second.join(10_000)
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
        val secondAttempting = CountDownLatch(1)
        val secondAcquired = AtomicBoolean(false)

        val first = thread {
            writeTransaction(db) {
                takeInstanceLock()
                firstHoldsLock.countDown()
                releaseFirst.await()
            }
        }
        val second = thread {
            firstHoldsLock.await()
            secondAttempting.countDown()
            writeTransaction(db) {
                takeInstanceLock()
                secondAcquired.set(true)
            }
        }

        secondAttempting.await()
        Thread.sleep(300)
        assertFalse(secondAcquired.get(), "two writers held the instance lock at once")

        releaseFirst.countDown()
        first.join(10_000)
        second.join(10_000)
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
