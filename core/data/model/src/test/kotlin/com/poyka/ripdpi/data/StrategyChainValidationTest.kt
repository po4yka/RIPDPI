package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyChainValidationTest {
    @Test
    fun `send steps after seqovl are rejected before execution`() {
        for (kind in listOf("split", "disorder", "fake", "oob", "disoob", "syndata", "fakedsplit", "hostfake")) {
            val result = parseStrategyChainDsl("seqovl 2\n$kind 4")

            assertTrue(kind, result.isFailure)
            assertEquals("seqovl must be the last tcp send step", result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun `TLS prelude can precede terminal seqovl`() {
        val chain = parseStrategyChainDsl("tlsrec extlen\nseqovl host+1 overlap=12 fake=profile").getOrThrow()

        assertEquals(listOf(TcpChainStepKind.TlsRec, TcpChainStepKind.SeqOverlap), chain.tcpSteps.map { it.kind })
    }

    @Test
    fun `dummy prepend accepts bounded counts and rejects excess per step or chain`() {
        for (counts in listOf(listOf(0), listOf(64), listOf(32, 32))) {
            validateUdpChain(counts.map { UdpChainStepModel(kind = UdpChainStepKind.DummyPrepend, count = it) })
        }
        for (counts in listOf(listOf(65), listOf(32, 33))) {
            val result =
                runCatching {
                    validateUdpChain(counts.map { UdpChainStepModel(kind = UdpChainStepKind.DummyPrepend, count = it) })
                }
            assertTrue(result.isFailure)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("dummy_prepend"),
            )
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("64"),
            )
        }
    }
}
