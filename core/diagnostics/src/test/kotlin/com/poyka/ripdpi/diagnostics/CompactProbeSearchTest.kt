package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompactProbeSearchTest {
    @Test
    fun `annealing keeps controls and finds the cheaper complete cover reproducibly`() {
        val probes =
            listOf(
                CompactProbe("control", setOf("control"), 1.0, mandatory = true),
                CompactProbe("slow", setOf("tls", "quic"), 100.0, mandatory = false),
                CompactProbe("fast", setOf("tls", "quic"), 2.0, mandatory = false),
            )
        val expected = setOf("control", "fast")
        assertEquals(expected, CompactProbeSearch.select(probes, 2, seed = 42))
        assertEquals(expected, CompactProbeSearch.select(probes, 2, seed = 42))
    }

    @Test
    fun `infeasible coverage never returns an incomplete set`() {
        val probes =
            listOf(
                CompactProbe("dns", setOf("dns"), 1.0, mandatory = false),
                CompactProbe("tls", setOf("tls"), 1.0, mandatory = false),
            )
        assertNull(CompactProbeSearch.select(probes, 1, seed = 42))
        assertNull(CompactProbeSearch.select(probes.map { it.copy(mandatory = true) }, 1, seed = 42))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nonfinite cost is rejected`() {
        CompactProbeSearch.select(listOf(CompactProbe("bad", emptySet(), Double.NaN, false)), 1, 42)
    }

    @Test
    fun `minimum cardinality preserves required matrix sizes`() {
        val probes = (1..4).map { CompactProbe("$it", setOf("same"), it.toDouble(), false) }
        assertEquals(setOf("1", "2", "3"), CompactProbeSearch.select(probes, 3, seed = 42, minimum = 3))
    }
}
