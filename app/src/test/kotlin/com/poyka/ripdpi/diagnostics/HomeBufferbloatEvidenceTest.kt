package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeBufferbloatEvidenceTest {
    private val idle = listOf(HomeRttSample(0, 10, 10))
    private val loaded = listOf(HomeRttSample(30, 50, 20))

    @Test
    fun `failed or empty load cannot produce a bufferbloat grade`() {
        listOf(
            HomeLoadEvidence(false, 100, 20, 60),
            HomeLoadEvidence(true, 0, 20, 60),
        ).forEach { load ->
            val result = homeBufferbloatResult(idle, loaded, load)
            assertEquals(HomeBufferbloatGrade.UNKNOWN, result.grade)
            assertNull(result.loadedRttMs)
            assertNull(result.deltaMs)
        }
    }

    @Test
    fun `successful download outside RTT window is unknown`() {
        val result = homeBufferbloatResult(idle, loaded, HomeLoadEvidence(true, 100, 60, 90))
        assertEquals(HomeBufferbloatGrade.UNKNOWN, result.grade)
    }

    @Test
    fun `grade uses only RTT samples fully inside observed transfer`() {
        val result =
            homeBufferbloatResult(
                idle,
                loaded + HomeRttSample(10, 25, 1_000) + HomeRttSample(55, 75, 1_000),
                HomeLoadEvidence(true, 100, 20, 60),
            )
        assertEquals(HomeBufferbloatGrade.B, result.grade)
        assertEquals(20, result.loadedRttMs)
        assertEquals(10, result.deltaMs)
    }
}
