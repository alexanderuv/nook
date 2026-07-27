package io.nook.core.catalog

import io.nook.contract.CatalogClient
import io.nook.contract.OperationCatalog
import io.nook.core.db.EmbeddedPostgresSupport
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance

/**
 * What every behavior suite of the write and read paths sits on, so that each
 * one runs twice: once against the operations inside the core's own process,
 * and once against them across the connection.
 *
 * The two runs differ in [reach] and in nothing else. Every assertion is the one
 * the write or read path already had, unedited — which is the point: an
 * assertion changed while a suite was being made to run twice would hide
 * whether the connection or the change broke something.
 *
 * A suite reads state back through the tables rather than through the reads, as
 * it always has, so [db] stays available to it. That works for both runs because
 * the core under test is in this process either way; what crosses the connection
 * is the call, not the store.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class CatalogBehavior {

    /** The two ways a suite reaches the operations. */
    enum class Reach { IN_PROCESS, ACROSS_THE_CONNECTION }

    protected abstract val reach: Reach

    private val closing = mutableListOf<AutoCloseable>()

    /**
     * One core per store, so that two callers to one store are two callers to
     * one core — which is what the checks about several callers at once are
     * actually about.
     */
    private val cores = mutableMapOf<Database, RunningCore>()

    /** This suite's own freshly migrated store, one for each of the two runs. */
    protected val db: Database by lazy { freshStore() }

    /** How this suite reaches the eleven operations. */
    protected val service: OperationCatalog by lazy { callerTo(db) }

    /**
     * A second caller, for the suites that drive two at once. In the core's own
     * process there is nothing to keep apart, so this is a second catalog over
     * the same store; across the connection it is a second client with its own
     * connection to the one core.
     */
    protected val otherService: OperationCatalog by lazy { callerTo(db) }

    /** A store nothing else touches, and a caller of this run's kind pointed at it. */
    data class OwnInstance(val db: Database, val service: OperationCatalog)

    /**
     * An instance to itself, reached the same way as [service] — for the checks
     * whose subject is the whole instance rather than one project, where a
     * project another test created would widen the answer.
     */
    protected fun ownInstance(): OwnInstance {
        val store = freshStore()
        return OwnInstance(store, callerTo(store))
    }

    private fun callerTo(store: Database): OperationCatalog = when (reach) {
        Reach.IN_PROCESS -> CoreCatalog(store)
        Reach.ACROSS_THE_CONNECTION -> CatalogClient(coreFor(store).address).also { closing += it }
    }

    private fun coreFor(store: Database): RunningCore =
        cores.getOrPut(store) { RunningCore(store).also { closing += it } }

    private fun freshStore(): Database = Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase())

    @AfterAll
    fun letGoOfWhatWasOpened() {
        closing.asReversed().forEach { it.close() }
        closing.clear()
    }
}

/** One core listening on a port this machine picks, shared by a store's callers. */
private class RunningCore(db: Database) : AutoCloseable {
    private val server = CatalogServer(CoreCatalog(db), LOOPBACK, port = 0)
    val address: String = server.start()
    override fun close(): Unit = server.close()
}
