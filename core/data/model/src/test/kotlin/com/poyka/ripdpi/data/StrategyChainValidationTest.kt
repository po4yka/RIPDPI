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
}
