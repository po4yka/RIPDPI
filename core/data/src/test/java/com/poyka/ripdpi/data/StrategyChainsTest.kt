package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyChainsTest {
    @Test
    fun `adaptive markers round trip through tcp dsl and humanized summary`() {
        val dsl =
            """
            [tcp]
            tlsrec auto(sniext)
            split auto(balanced)
            """.trimIndent()

        val parsed = parseStrategyChainDsl(dsl).getOrThrow()

        assertEquals(
            listOf(
                TcpChainStepModel(TcpChainStepKind.TlsRec, AdaptiveMarkerSniExt),
                TcpChainStepModel(TcpChainStepKind.Split, AdaptiveMarkerBalanced),
            ),
            parsed.tcpSteps,
        )
        assertEquals(dsl, formatStrategyChainDsl(parsed.tcpSteps, parsed.udpSteps))
        assertEquals(
            "tcp: tlsrec(adaptive TLS SNI extension) -> split(adaptive balanced)",
            formatChainSummary(parsed.tcpSteps, parsed.udpSteps),
        )
    }

    @Test
    fun `rewritePrimaryTcpMarker only updates first non tls step`() {
        val updated =
            rewritePrimaryTcpMarker(
                tcpSteps =
                    listOf(
                        TcpChainStepModel(TcpChainStepKind.TlsRec, "extlen"),
                        TcpChainStepModel(TcpChainStepKind.Split, "host+1"),
                        TcpChainStepModel(TcpChainStepKind.Fake, "endhost"),
                    ),
                marker = AdaptiveMarkerBalanced,
            )

        assertEquals("extlen", updated[0].marker)
        assertEquals(AdaptiveMarkerBalanced, updated[1].marker)
        assertEquals("endhost", updated[2].marker)
    }

    @Test
    fun `hostfake parser rejects adaptive markers`() {
        val markerResult = parseStrategyChainDsl("[tcp]\nhostfake auto(host)")
        val midhostResult = parseStrategyChainDsl("[tcp]\nhostfake endhost midhost=auto(midsld)")

        assertTrue(markerResult.isFailure)
        assertFalse(markerResult.exceptionOrNull()?.message.isNullOrBlank())
        assertTrue(midhostResult.isFailure)
        assertFalse(midhostResult.exceptionOrNull()?.message.isNullOrBlank())
    }

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

    @Test
    fun `fake approximation steps round trip through dsl and legacy projection`() {
        val dsl =
            """
            [tcp]
            tlsrec extlen
            fakedsplit auto(host) when_round=1-2
            """.trimIndent()

        val parsed = parseStrategyChainDsl(dsl).getOrThrow()
        val settings =
            AppSettings
                .newBuilder()
                .setStrategyChains(parsed.tcpSteps, parsed.udpSteps)
                .build()

        assertEquals(
            listOf(
                TcpChainStepModel(TcpChainStepKind.TlsRec, "extlen"),
                TcpChainStepModel(
                    kind = TcpChainStepKind.FakeSplit,
                    marker = AdaptiveMarkerHost,
                    activationFilter = ActivationFilterModel(round = NumericRangeModel(1, 2)),
                ),
            ),
            parsed.tcpSteps,
        )
        assertEquals(dsl, formatStrategyChainDsl(parsed.tcpSteps, parsed.udpSteps))
        assertEquals(
            "tcp: tlsrec(extlen) -> fakedsplit(adaptive host/SNI start round=1-2)",
            formatChainSummary(parsed.tcpSteps, parsed.udpSteps),
        )
        assertEquals("fake", settings.desyncMethod)
        assertEquals(AdaptiveMarkerHost, settings.splitMarker)
    }

    @Test
    fun `fake approximation steps must be terminal in tcp chain`() {
        val fakedsplitResult = parseStrategyChainDsl("[tcp]\nfakedsplit host+1\nsplit endhost")
        val fakeddisorderResult = parseStrategyChainDsl("[tcp]\nfakeddisorder host+1\nfake endhost")

        assertTrue(fakedsplitResult.isFailure)
        assertTrue(fakeddisorderResult.isFailure)
    }

    @Test
    fun `tlsrandrec dsl round trip preserves fragment options and legacy projection`() {
        val dsl =
            """
            [tcp]
            tlsrandrec sniext+4 count=5 min=24 max=48
            split host+1
            """.trimIndent()

        val parsed = parseStrategyChainDsl(dsl).getOrThrow()

        assertEquals(
            listOf(
                TcpChainStepModel(
                    kind = TcpChainStepKind.TlsRandRec,
                    marker = "sniext+4",
                    fragmentCount = 5,
                    minFragmentSize = 24,
                    maxFragmentSize = 48,
                ),
                TcpChainStepModel(TcpChainStepKind.Split, "host+1"),
            ),
            parsed.tcpSteps,
        )
        assertEquals(dsl, formatStrategyChainDsl(parsed.tcpSteps, parsed.udpSteps))

        val settings =
            AppSettings
                .newBuilder()
                .setStrategyChains(parsed.tcpSteps, parsed.udpSteps)
                .build()

        assertEquals("split", settings.desyncMethod)
        assertTrue(settings.tlsrecEnabled)
        assertEquals("sniext+4", settings.tlsrecMarker)
        assertEquals(
            "tcp: tlsrandrec(sniext+4 count=5 min=24 max=48) -> split(host+1)",
            settings.effectiveChainSummary(),
        )
    }

    @Test
    fun `tlsrandrec parser rejects missing knobs`() {
        val result = parseStrategyChainDsl("[tcp]\ntlsrandrec extlen count=4 min=16")

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()?.message.isNullOrBlank())
    }

    @Test
    fun `replaceTlsPreludeTcpChainSteps replaces stacked preludes and preserves send steps`() {
        val updated =
            replaceTlsPreludeTcpChainSteps(
                tcpSteps =
                    listOf(
                        TcpChainStepModel(TcpChainStepKind.TlsRec, "extlen"),
                        TcpChainStepModel(
                            kind = TcpChainStepKind.TlsRandRec,
                            marker = "sniext+4",
                            fragmentCount = 5,
                            minFragmentSize = 24,
                            maxFragmentSize = 48,
                        ),
                        TcpChainStepModel(TcpChainStepKind.HostFake, "endhost+8", fakeHostTemplate = "googlevideo.com"),
                        TcpChainStepModel(TcpChainStepKind.Split, "midsld"),
                    ),
                newPreludeSteps =
                    listOf(
                        TcpChainStepModel(
                            kind = TcpChainStepKind.TlsRandRec,
                            marker = "extlen",
                            fragmentCount = 4,
                            minFragmentSize = 16,
                            maxFragmentSize = 96,
                        ),
                    ),
            )

        assertEquals(
            listOf(
                TcpChainStepModel(
                    kind = TcpChainStepKind.TlsRandRec,
                    marker = "extlen",
                    fragmentCount = 4,
                    minFragmentSize = 16,
                    maxFragmentSize = 96,
                ),
                TcpChainStepModel(TcpChainStepKind.HostFake, "endhost+8", fakeHostTemplate = "googlevideo.com"),
                TcpChainStepModel(TcpChainStepKind.Split, "midsld"),
            ),
            updated,
        )
    }

    @Test
    fun `dsl round trip preserves activation filters on tcp and udp steps`() {
        val dsl =
            """
            [tcp]
            tlsrandrec sniext+4 count=5 min=24 max=48 when_stream=0-1199
            fake host when_round=1-2 when_size=64-512

            [udp]
            fake_burst 3 when_round=1-3 when_stream=0-1199
            """.trimIndent()

        val parsed = parseStrategyChainDsl(dsl).getOrThrow()

        assertEquals(
            listOf(
                TcpChainStepModel(
                    kind = TcpChainStepKind.TlsRandRec,
                    marker = "sniext+4",
                    fragmentCount = 5,
                    minFragmentSize = 24,
                    maxFragmentSize = 48,
                    activationFilter =
                        ActivationFilterModel(
                            streamBytes = NumericRangeModel(start = 0, end = 1199),
                        ),
                ),
                TcpChainStepModel(
                    kind = TcpChainStepKind.Fake,
                    marker = "host",
                    activationFilter =
                        ActivationFilterModel(
                            round = NumericRangeModel(start = 1, end = 2),
                            payloadSize = NumericRangeModel(start = 64, end = 512),
                        ),
                ),
            ),
            parsed.tcpSteps,
        )
        assertEquals(
            listOf(
                UdpChainStepModel(
                    count = 3,
                    activationFilter =
                        ActivationFilterModel(
                            round = NumericRangeModel(start = 1, end = 3),
                            streamBytes = NumericRangeModel(start = 0, end = 1199),
                        ),
                ),
            ),
            parsed.udpSteps,
        )
        assertEquals(dsl, formatStrategyChainDsl(parsed.tcpSteps, parsed.udpSteps))
        assertEquals(
            "tcp: tlsrandrec(sniext+4 count=5 min=24 max=48 stream=0-1199) -> fake(host round=1-2 size=64-512) | " +
                "udp: fake_burst(3 round=1-3 stream=0-1199)",
            formatChainSummary(parsed.tcpSteps, parsed.udpSteps),
        )
    }
}
