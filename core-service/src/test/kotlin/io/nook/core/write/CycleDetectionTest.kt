package io.nook.core.write

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The pure cycle walk over the shapes the concurrency investigation used —
 * a chain, a diamond, a safe edge — plus the whole-set replacement
 * semantics. No database involved.
 */
class CycleDetectionTest {

    private val a = Uuid.random()
    private val b = Uuid.random()
    private val c = Uuid.random()
    private val d = Uuid.random()
    private val e = Uuid.random()

    @Test
    fun `closing a three-item chain is a cycle`() {
        // b waits on a, c waits on b; making a wait on c closes the loop.
        val edges = mapOf(b to setOf(a), c to setOf(b))
        assertTrue(wouldCreateCycle(edges, a, setOf(c)))
    }

    @Test
    fun `an edge that only fans out further is safe`() {
        val edges = mapOf(b to setOf(a), c to setOf(b))
        assertFalse(wouldCreateCycle(edges, c, setOf(b, a)))
    }

    @Test
    fun `a diamond does not confuse the walk`() {
        // b and c both wait on a; d waits on both. No loop anywhere.
        val edges = mapOf(b to setOf(a), c to setOf(a), d to setOf(b, c))
        assertFalse(wouldCreateCycle(edges, e, setOf(a, d)))
        // But making a wait on d closes the loop through both arms.
        assertTrue(wouldCreateCycle(edges, a, setOf(d)))
    }

    @Test
    fun `the walk judges the replacement set, not the replaced one`() {
        // a waits on b. Making b wait on a closes the two-item loop...
        val edges = mapOf(a to setOf(b))
        assertTrue(wouldCreateCycle(edges, b, setOf(a)))
        // ...but replacing b's set with something loop-free is fine, whatever
        // b's old edges were.
        assertFalse(wouldCreateCycle(mapOf(a to setOf(b), b to setOf(c)), b, setOf(c)))
    }

    @Test
    fun `re-supplying the current loop-free set is accepted`() {
        val edges = mapOf(b to setOf(a))
        assertFalse(wouldCreateCycle(edges, b, setOf(a)))
    }
}
