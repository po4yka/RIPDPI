package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyChainValidationTest {
    @Test
    fun `packet number gap count is bounded before execution`() {
        for (count in listOf(0, 64)) {
            val chain = parseStrategyChainDsl("[udp]\nquic_packet_number_gap $count").getOrThrow()

            assertEquals(count, chain.udpSteps.single().count)
        }
        for (count in listOf(65, Int.MAX_VALUE)) {
            val result = parseStrategyChainDsl("[udp]\nquic_packet_number_gap $count")

            assertTrue(result.isFailure)
            assertEquals(
                "quic_packet_number_gap count must be between 0 and 64",
                result.exceptionOrNull()?.message,
            )
        }
    }

    @Test
    fun `packet number gap chain has one total packet budget`() {
        val prefix = "[udp]\nquic_packet_number_gap 32\nquic_packet_number_gap "
        val accepted = parseStrategyChainDsl("${prefix}32").getOrThrow()
        val rejected = parseStrategyChainDsl("${prefix}33")

        assertEquals(listOf(32, 32), accepted.udpSteps.map { it.count })
        assertTrue(rejected.isFailure)
        assertEquals(
            "quic_packet_number_gap total count must be at most 64",
            rejected.exceptionOrNull()?.message,
        )
    }

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

    @Test
    fun `CID churn count and chain total stay within the budget`() {
        for (count in listOf(0, 1, 64)) {
            val chain = parseStrategyChainDsl("[udp]\nquic_cid_churn $count").getOrThrow()
            assertEquals(count, chain.udpSteps.single().count)
        }
        for (count in listOf(65, Int.MAX_VALUE)) {
            assertTrue(parseStrategyChainDsl("[udp]\nquic_cid_churn $count").isFailure)
        }
        assertTrue(parseStrategyChainDsl("[udp]\nquic_cid_churn 32\nquic_cid_churn 33").isFailure)
        assertTrue(parseStrategyChainDsl("[udp]\nquic_cid_churn 32\nquic_cid_churn 32").isSuccess)
    }

    @Test
    fun `CID churn model counts cannot exceed the budget`() {
        for (count in listOf(65, Int.MAX_VALUE)) {
            val result =
                runCatching {
                    validateUdpChain(listOf(UdpChainStepModel(count = count, kind = UdpChainStepKind.QuicCidChurn)))
                }
            assertTrue("count $count", result.isFailure)
        }
    }

    @Test
    fun `QUIC crypto split count is bounded before execution`() {
        for (count in listOf(0, 1, 64)) {
            assertTrue(parseStrategyChainDsl("[udp]\nquic_crypto_split $count").isSuccess)
        }

        for (count in listOf(65, Int.MAX_VALUE)) {
            val result = parseStrategyChainDsl("[udp]\nquic_crypto_split $count")

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("quic_crypto_split") == true)
        }
    }

    @Test
    fun `QUIC crypto split counts share a chain budget`() {
        assertTrue(parseStrategyChainDsl("[udp]\nquic_crypto_split 32\nquic_crypto_split 32").isSuccess)

        val result = parseStrategyChainDsl("[udp]\nquic_crypto_split 32\nquic_crypto_split 33")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("quic_crypto_split") == true)
    }

    @Test
    fun `QUIC version decoy repeats have a per chain budget`() {
        val kind = UdpChainStepKind.QuicVersionNegotiationDecoy
        for (count in listOf(-1, 65, Int.MAX_VALUE)) {
            assertTrue(
                "count $count",
                runCatching { validateUdpChain(listOf(UdpChainStepModel(count, kind))) }.isFailure,
            )
        }
        for (counts in listOf(listOf(0), listOf(64), listOf(32, 32))) {
            validateUdpChain(counts.map { UdpChainStepModel(it, kind) })
        }
        assertTrue(
            runCatching {
                validateUdpChain(listOf(UdpChainStepModel(32, kind), UdpChainStepModel(33, kind)))
            }.isFailure,
        )
        validateUdpChain(
            listOf(UdpChainStepModel(64, kind), UdpChainStepModel(65, UdpChainStepKind.QuicSniSplit)),
        )
    }
}
