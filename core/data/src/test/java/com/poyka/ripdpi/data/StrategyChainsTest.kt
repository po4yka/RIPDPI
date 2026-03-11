package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `hostfake dsl round trip preserves midhost and template`() {
        val dsl =
            """
            [tcp]
            hostfake endhost+8 midhost=midsld host=googlevideo.com
            split midsld
            """.trimIndent()

        val parsed = parseStrategyChainDsl(dsl).getOrThrow()

        assertEquals(
            listOf(
                TcpChainStepModel(
                    kind = TcpChainStepKind.HostFake,
                    marker = "endhost+8",
                    midhostMarker = "midsld",
                    fakeHostTemplate = "googlevideo.com",
                ),
                TcpChainStepModel(TcpChainStepKind.Split, "midsld"),
            ),
            parsed.tcpSteps,
        )
        assertEquals(dsl, formatStrategyChainDsl(parsed.tcpSteps, parsed.udpSteps))
        assertEquals(
            "tcp: hostfake(endhost+8 midhost=midsld host=googlevideo.com) -> split(midsld)",
            formatChainSummary(parsed.tcpSteps, parsed.udpSteps),
        )
    }

    @Test
    fun `hostfake strategy chains project legacy fake compatibility while preserving proto fields`() {
        val settings =
            AppSettings
                .newBuilder()
                .setStrategyChains(
                    tcpSteps =
                        listOf(
                            TcpChainStepModel(
                                kind = TcpChainStepKind.HostFake,
                                marker = "endhost+4",
                                midhostMarker = "midsld",
                                fakeHostTemplate = "googlevideo.com",
                            ),
                        ),
                    udpSteps = emptyList(),
                ).build()

        assertEquals("fake", settings.desyncMethod)
        assertEquals("endhost+4", settings.splitMarker)
        assertEquals(1, settings.tcpChainStepsCount)
        assertEquals("midsld", settings.tcpChainStepsList[0].midhostMarker)
        assertEquals("googlevideo.com", settings.tcpChainStepsList[0].fakeHostTemplate)
        assertEquals(
            listOf(
                TcpChainStepModel(
                    kind = TcpChainStepKind.HostFake,
                    marker = "endhost+4",
                    midhostMarker = "midsld",
                    fakeHostTemplate = "googlevideo.com",
                ),
            ),
            settings.effectiveTcpChainSteps(),
        )
    }

    @Test
    fun `hostfake parser rejects invalid template`() {
        val result = parseStrategyChainDsl("[tcp]\nhostfake endhost host=127.0.0.1")

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()?.message.isNullOrBlank())
    }
}
