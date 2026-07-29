package io.nook.core.catalog

import io.nook.contract.BreakdownException
import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.FieldChange
import io.nook.contract.UpdateItem
import io.nook.core.db.EmbeddedPostgresSupport
import io.nook.core.write.readItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * A caller that gives up while the core is writing an item and its blocker
 * edges, a hundred times over: afterwards the item carries both the change and
 * the edges, or neither — never a row whose edges never arrived.
 *
 * Nothing was built for this. What guarantees it is the transaction the write
 * path already opens: the row and its edges become permanent together or not at
 * all, and store work that blocks the thread it runs on cannot be interrupted by
 * the caller leaving. This is that claim executed, not a mechanism under test.
 *
 * The abandoned write is an `update_item` because that is the operation which
 * writes a row and its edges together — the one shape in this catalog where
 * "the item without its edges" is a state the store could be left in if the
 * transaction did not hold.
 */
class CatalogAbandonedWriteTest {

    private companion object {
        const val RUNS = 100

        val db by lazy { Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase()) }
        val inProcess by lazy { CoreCatalog(db).forActor(CatalogBehavior.SOMEBODY) }
    }

    @Test
    fun `a caller giving up mid-write leaves the item whole with its edges, or untouched`() {
        val project = inProcess.createProject(CreateProject("Abandoned Mid-Write"))
        val blocker = inProcess.createItem(project.slug, CreateItem(type = "task", name = "The blocker"))

        var gaveUp = 0
        // Short enough that the caller is gone while the core is still inside the
        // write, and short enough to be reached before the reply comes back.
        connectionTo(db, waitLimit = 3.milliseconds).use { connection ->
            repeat(RUNS) { run ->
                inProcess.createItem(project.slug, CreateItem(type = "task", name = "Run $run"))
                val outcome = runCatching {
                    connection.caller.updateItem(
                        project.slug,
                        "run-$run",
                        UpdateItem(
                            name = FieldChange.Set("Run $run, written"),
                            blockedBy = FieldChange.Set(listOf(blocker.slug)),
                        ),
                    )
                }
                if (outcome.exceptionOrNull() is BreakdownException) gaveUp++
            }
        }

        assertTrue(gaveUp > 0, "no caller gave up, so nothing was abandoned to check")

        repeat(RUNS) { run ->
            val item = readItem(db, project.slug, "run-$run")
            val written = item.name == "Run $run, written"
            assertEquals(
                written,
                item.blockedBy == setOf(blocker.id),
                "run $run left the item half-written: name=\"${item.name}\", blockers=${item.blockedBy}",
            )
        }
    }
}
