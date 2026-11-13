package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyChainsIpFragTest {
    @Test
    fun `ipfrag dsl round trip preserves normalized summaries`() {
        val dsl =
            """
            [tcp]
            tlsrec extlen
            ipfrag2 host+2

            [udp]
            ipfrag2_udp 8 when_round=2-3
            """.trimIndent()

        val parsed = parseStrategyChainDsl(dsl).getOrThrow()

        assertEquals(
            listOf(
                TcpChainStepModel(TcpChainStepKind.TlsRec, "extlen"),
                TcpChainStepModel(TcpChainStepKind.IpFrag2, "host+2"),
            ),
            parsed.tcpSteps,
        )
        assertEquals(
            listOf(
                UdpChainStepModel(
                    count = 0,
                    kind = UdpChainStepKind.IpFrag2Udp,
                    splitBytes = 8,
                    activationFilter = ActivationFilterModel(round = NumericRangeModel(start = 2, end = 3)),
                ),
            ),
            parsed.udpSteps,
        )
        assertTrue(parsed.usesIpFragmentation())
        assertEquals(dsl, formatStrategyChainDsl(parsed.tcpSteps, parsed.udpSteps))
        assertEquals(
            "tcp: tlsrec(extlen) -> ipfrag2(host+2) | udp: ipfrag2_udp(8 round=2-3)",
            formatChainSummary(parsed.tcpSteps, parsed.udpSteps),
        )
    }

    @Test
    fun `ipfrag usage is rejected outside vpn ui flow`() {
        val tcpSteps = listOf(TcpChainStepModel(TcpChainStepKind.IpFrag2, "host+2"))
        val udpSteps = listOf(UdpChainStepModel(count = 0, kind = UdpChainStepKind.IpFrag2Udp, splitBytes = 8))

        assertThrows(IllegalArgumentException::class.java) {
            validateStrategyChainUsage(
                tcpSteps,
                udpSteps,
                Mode.Proxy,
                useCommandLineSettings = false,
                rootModeEnabled = false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateStrategyChainUsage(
                tcpSteps,
                udpSteps,
                Mode.VPN,
                useCommandLineSettings = true,
                rootModeEnabled = false,
            )
        }

        validateStrategyChainUsage(
            tcpSteps,
            udpSteps,
            Mode.VPN,
            useCommandLineSettings = false,
            rootModeEnabled = false,
        )
    }

    @Test
    fun `fakerst usage requires root mode`() {
        val tcpSteps =
            listOf(
                TcpChainStepModel(TcpChainStepKind.FakeRst, "host+2"),
                TcpChainStepModel(TcpChainStepKind.Split, "host+1"),
            )

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                validateStrategyChainUsage(tcpSteps, emptyList(), Mode.VPN, false, rootModeEnabled = false)
            }
        assertEquals("fakerst requires root mode", error.message)

        validateStrategyChainUsage(tcpSteps, emptyList(), Mode.VPN, false, rootModeEnabled = true)
    }

    @Test
    fun `ipfrag steps must remain isolated within their chains`() {
        val invalidTcpChains =
            listOf(
                "ipfrag2 host+1\nsplit endhost",
                "split host+1\nipfrag2 endhost",
                "disorder host+1\nipfrag2 endhost",
                "ipfrag2 host+1\nipfrag2 endhost",
                "tlsrec extlen\nsplit host+1\nipfrag2 endhost",
            )
        invalidTcpChains.forEach { chain ->
            val tcpResult = parseStrategyChainDsl("[tcp]\n$chain")
            assertTrue(chain, tcpResult.isFailure)
        }
        val udpResult =
            parseStrategyChainDsl(
                """
                [udp]
                ipfrag2_udp 8
                fake_burst 1
                """.trimIndent(),
            )

        assertTrue(udpResult.isFailure)
    }
}
