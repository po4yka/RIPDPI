package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostFakeRandomModeTest {
    @Test
    fun `random hostfake mode survives DSL edits`() {
        val dsl = "[tcp]\nhostfake endhost random=true"
        val parsed = parseStrategyChainDsl(dsl).getOrThrow()

        assertTrue(parsed.tcpSteps.single().randomFakeHost)
        assertEquals(dsl, formatStrategyChainDsl(parsed.tcpSteps, parsed.udpSteps))
        assertTrue(formatChainSummary(parsed.tcpSteps, parsed.udpSteps).contains("random=true"))
    }

    @Test
    fun `random hostfake option rejects other kinds and invalid booleans`() {
        for (line in listOf("split 1 random=true", "fake 1 random=false", "hostfake endhost random=yes")) {
            assertTrue("Accepted invalid random option: $line", parseStrategyChainDsl("[tcp]\n$line").isFailure)
        }
    }

    @Test
    fun `random mode is omitted when false and cleared on other kinds`() {
        val falseMode = parseStrategyChainDsl("[tcp]\nhostfake endhost random=false").getOrThrow()
        val split = normalizeTcpChainStepModel(TcpChainStepModel(TcpChainStepKind.Split, "1", randomFakeHost = true))

        assertFalse(falseMode.tcpSteps.single().randomFakeHost)
        assertEquals("[tcp]\nhostfake endhost", formatStrategyChainDsl(falseMode.tcpSteps, falseMode.udpSteps))
        assertFalse(split.randomFakeHost)
    }
}
