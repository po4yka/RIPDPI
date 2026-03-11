package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyChainsTest {
    @Test
    fun `dsl round trip preserves tcp and udp chain order`() {
        val dsl =
            """
            [tcp]
            tlsrec extlen
            fake host+1
            split midsld

            [udp]
            fake_burst 3
            """.trimIndent()

        val parsed = parseStrategyChainDsl(dsl).getOrThrow()

        assertEquals(
            listOf(
                TcpChainStepModel(TcpChainStepKind.TlsRec, "extlen"),
                TcpChainStepModel(TcpChainStepKind.Fake, "host+1"),
                TcpChainStepModel(TcpChainStepKind.Split, "midsld"),
            ),
            parsed.tcpSteps,
        )
        assertEquals(listOf(UdpChainStepModel(count = 3)), parsed.udpSteps)
        assertEquals(dsl, formatStrategyChainDsl(parsed.tcpSteps, parsed.udpSteps))
    }

    @Test
    fun `legacy fields synthesize effective chains`() {
        val settings =
            AppSettings
                .newBuilder()
                .setDesyncMethod("fake")
                .setSplitMarker("host")
                .setTlsrecEnabled(true)
                .setTlsrecMarker("sniext+4")
                .setUdpFakeCount(2)
                .build()

        assertEquals(
            listOf(
                TcpChainStepModel(TcpChainStepKind.TlsRec, "sniext+4"),
                TcpChainStepModel(TcpChainStepKind.Fake, "host"),
            ),
            settings.effectiveTcpChainSteps(),
        )
        assertEquals(listOf(UdpChainStepModel(count = 2)), settings.effectiveUdpChainSteps())
    }

    @Test
    fun `setting strategy chains projects legacy compatibility fields`() {
        val settings =
            AppSettings
                .newBuilder()
                .setStrategyChains(
                    tcpSteps =
                        listOf(
                            TcpChainStepModel(TcpChainStepKind.TlsRec, "extlen"),
                            TcpChainStepModel(TcpChainStepKind.Split, "host+2"),
                        ),
                    udpSteps = listOf(UdpChainStepModel(count = 4)),
                ).build()

        assertEquals("split", settings.desyncMethod)
        assertEquals("host+2", settings.splitMarker)
        assertTrue(settings.tlsrecEnabled)
        assertEquals("extlen", settings.tlsrecMarker)
        assertEquals(4, settings.udpFakeCount)
        assertEquals("tcp: tlsrec(extlen) -> split(host+2) | udp: fake_burst(4)", settings.effectiveChainSummary())
    }
}
