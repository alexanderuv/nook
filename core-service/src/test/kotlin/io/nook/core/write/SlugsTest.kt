package io.nook.core.write

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pure slug rules, table-driven; no database involved. */
class SlugsTest {

    @Test
    fun `derivation lowercases, collapses, and trims`() {
        val cases = mapOf(
            "Add search" to "add-search",
            "Search Revamp!" to "search-revamp",
            "  spaced   out  " to "spaced-out",
            "v2.0 launch" to "v2-0-launch",
            "MiXeD CaSe" to "mixed-case",
            "trailing!!!" to "trailing",
            "???" to "",
            "" to "",
            "Añadir búsqueda" to "a-adir-b-squeda",
            "already-a-slug" to "already-a-slug",
        )
        cases.forEach { (name, expected) ->
            assertEquals(expected, deriveSlug(name), "deriving from \"$name\"")
        }
    }

    @Test
    fun `acceptable explicit slugs pass unaltered`() {
        val acceptable = listOf("q3-spike", "search-core", "a", "2fast", "3f2a", "a-b-c-1")
        acceptable.forEach { slug ->
            assertNull(explicitSlugProblem(slug), "expected \"$slug\" to be acceptable")
        }
    }

    @Test
    fun `unacceptable explicit slugs are named, never rewritten`() {
        val unacceptable = listOf(
            "",
            "Add-Search",
            "add_search",
            "add search",
            "café",
            "slash/slug",
            "3f2a8c1e-4b6d-4b0a-9f3e-2d1c0b9a8f7e",
        )
        unacceptable.forEach { slug ->
            val problem = explicitSlugProblem(slug)
            assertEquals(true, problem != null, "expected \"$slug\" to be rejected")
        }
    }

    @Test
    fun `suffix allocation picks the first free number`() {
        assertEquals("add-search", firstFreeSlug("add-search", emptySet()))
        assertEquals("add-search-2", firstFreeSlug("add-search", setOf("add-search")))
        assertEquals(
            "add-search-3",
            firstFreeSlug("add-search", setOf("add-search", "add-search-2")),
        )
        assertEquals(
            "add-search-2",
            firstFreeSlug("add-search", setOf("add-search", "add-search-3")),
        )
    }
}
