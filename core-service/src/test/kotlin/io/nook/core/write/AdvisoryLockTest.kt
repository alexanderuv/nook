package io.nook.core.write

import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.db.ProjectItemTable
import io.nook.core.db.ProjectTable
import kotlin.uuid.Uuid
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Proves the advisory lock serializes writers on one project: while the first
 * transaction holds the lock, the second provably waits, and once it gets the
 * lock it sees the first transaction's committed work.
 */
class AdvisoryLockTest {

    @Test
    fun `a second writer waits for the lock and then sees the first writer's commit`() {
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
                takeProjectLock(projectId)
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
                takeProjectLock(projectId)
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
}
