package io.nook.core.db

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.core.catalog.CatalogBehavior.Companion.SOMEBODY
import io.nook.core.catalog.CoreCatalog
import io.nook.core.read.ReadService
import io.nook.core.write.WriteService
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * A timestamp names a moment, not a reading of a clock — and the difference is
 * only visible across machines that keep different time.
 *
 * A zoneless column loses the distinction silently. Writing an `Instant` into
 * one stores the wall clock of whichever JVM wrote it, and reading it back
 * rebuilds an `Instant` from the zone of whichever JVM reads it, so one row
 * answers differently on two hosts and neither of them can tell. Nothing in the
 * schema, the declarations, or the drift check can see that difference: it
 * shows up only by asking the same row the same question from two zones, which
 * is what these tests do.
 *
 * The default zone is process-wide state, so each shift is undone before the
 * next assertion runs.
 */
class TimestampsAreAbsoluteTest {

    private val db = Database.connect(EmbeddedPostgresSupport.freshMigratedDatabase())
    private val writes = CoreCatalog(db).forActor(SOMEBODY)
    private val reads = ReadService(db)

    /** Zones spread far enough apart that any wall-clock reading disagrees. */
    private val zones = listOf("UTC", "America/Chicago", "Asia/Tokyo", "Pacific/Kiritimati")

    private fun <T> inZone(zone: String, block: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        return try {
            block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `a project's timestamps read the same whatever zone the reading machine keeps`() {
        val written = inZone("America/Chicago") { writes.createProject(CreateProject("Zone Proof")) }

        val readings = zones.associateWith { zone -> inZone(zone) { reads.getProject("zone-proof") } }

        assertEquals(
            setOf(written.createdAt),
            readings.values.map { it.createdAt }.toSet(),
            "one row named a different moment per reading zone: " +
                readings.mapValues { (_, project) -> project.createdAt },
        )
        assertEquals(setOf(written.updatedAt), readings.values.map { it.updatedAt }.toSet())
    }

    @Test
    fun `an item's timestamps read the same whatever zone the reading machine keeps`() {
        inZone("Asia/Tokyo") {
            writes.createProject(CreateProject("Item Zone Proof"))
        }
        val written = inZone("Asia/Tokyo") {
            writes.createItem("item-zone-proof", CreateItem(type = "task", name = "Timed"))
        }

        val readings = zones.associateWith { zone ->
            inZone(zone) { reads.getItem("item-zone-proof", "timed") }
        }

        assertEquals(
            setOf(written.createdAt),
            readings.values.map { it.createdAt }.toSet(),
            "one row named a different moment per reading zone: " +
                readings.mapValues { (_, item) -> item.createdAt },
        )
    }

    @Test
    fun `the zone the writer's machine kept leaves no trace in the moment stored`() {
        val moments = zones.map { zone ->
            val slug = "written-in-${zone.lowercase().replace(Regex("[^a-z0-9]+"), "-")}"
            inZone(zone) {
                writes.createProject(CreateProject(name = "Written in $zone", slug = slug))
            }.createdAt
        }

        // Four projects created in immediate succession from four zones. Their
        // moments differ only by how long the writes took; a zoneless column
        // would spread them across the better part of a day instead.
        val spread = java.time.Duration.between(moments.min(), moments.max())
        kotlin.test.assertTrue(
            spread < java.time.Duration.ofMinutes(1),
            "creation moments spread over $spread, so the writer's zone leaked into the value",
        )
    }
}
