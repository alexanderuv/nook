package io.nook.contract

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins every enum member to the exact integer the database stores, as
 * documented at the top of the initial-schema changelog. Whole-map equality:
 * a wrong code, a missing member, or a stray extra member all fail.
 */
class EnumCodesTest {

    @Test
    fun `item type codes match the stored integers`() {
        assertEquals(
            mapOf<Short, ItemType>(
                1.toShort() to ItemType.EPIC,
                2.toShort() to ItemType.TASK,
                3.toShort() to ItemType.BUG,
                4.toShort() to ItemType.CHORE,
            ),
            ItemType.entries.associateBy { it.code },
        )
    }

    @Test
    fun `item status codes match the stored integers`() {
        assertEquals(
            mapOf<Short, ItemStatus>(
                1.toShort() to ItemStatus.TODO,
                2.toShort() to ItemStatus.IN_PROGRESS,
                3.toShort() to ItemStatus.DONE,
                4.toShort() to ItemStatus.CANCELLED,
            ),
            ItemStatus.entries.associateBy { it.code },
        )
    }

    @Test
    fun `release status codes match the stored integers`() {
        assertEquals(
            mapOf<Short, ReleaseStatus>(
                1.toShort() to ReleaseStatus.PLANNED,
                2.toShort() to ReleaseStatus.IN_PROGRESS,
                3.toShort() to ReleaseStatus.RELEASED,
                4.toShort() to ReleaseStatus.CANCELLED,
            ),
            ReleaseStatus.entries.associateBy { it.code },
        )
    }

    @Test
    fun `labels are the caller vocabulary`() {
        assertEquals(
            listOf("epic", "task", "bug", "chore"),
            ItemType.entries.map { it.label },
        )
        assertEquals(
            listOf("todo", "in_progress", "done", "cancelled"),
            ItemStatus.entries.map { it.label },
        )
        assertEquals(
            listOf("planned", "in_progress", "released", "cancelled"),
            ReleaseStatus.entries.map { it.label },
        )
        assertEquals(
            listOf("validation_failed", "not_found", "conflict", "cycle"),
            ErrorCode.entries.map { it.label },
        )
    }
}
