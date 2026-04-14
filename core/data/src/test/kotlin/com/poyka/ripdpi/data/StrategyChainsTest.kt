package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun `rewritePrimaryTcpMarker leaves multidisorder chain unchanged`() {
        val original =
            listOf(
                TcpChainStepModel(TcpChainStepKind.TlsRec, "extlen"),
                TcpChainStepModel(TcpChainStepKind.MultiDisorder, "sniext"),
                TcpChainStepModel(TcpChainStepKind.MultiDisorder, "host"),
            )

        val updated = rewritePrimaryTcpMarker(original, AdaptiveMarkerBalanced)

        assertEquals(original, updated)
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
    fun `seqovl dsl round trip preserves overlap and fake mode`() {
        val dsl =
            """
            [tcp]
            tlsrec extlen
            seqovl midsld overlap=16 fake=rand
            """.trimIndent()

        val parsed = parseStrategyChainDsl(dsl).getOrThrow()

        assertEquals(
            listOf(
                TcpChainStepModel(TcpChainStepKind.TlsRec, "extlen"),
                TcpChainStepModel(
                    kind = TcpChainStepKind.SeqOverlap,
                    marker = "midsld",
                    overlapSize = 16,
                    fakeMode = SeqOverlapFakeModeRand,
                ),
            ),
            parsed.tcpSteps,
        )
        assertEquals(dsl, formatStrategyChainDsl(parsed.tcpSteps, parsed.udpSteps))
        assertEquals(
            "tcp: tlsrec(extlen) -> seqovl(midsld overlap=16 fake=rand)",
            formatChainSummary(parsed.tcpSteps, parsed.udpSteps),
        )
        assertEquals("seqovl", primaryDesyncMethod(parsed.tcpSteps))
    }

    @Test
    fun `seqovl validation rejects duplicates and non leading send steps`() {
        val duplicate =
            listOf(
                TcpChainStepModel(TcpChainStepKind.SeqOverlap, "host+1", overlapSize = 12, fakeMode = "profile"),
                TcpChainStepModel(TcpChainStepKind.SeqOverlap, "midsld", overlapSize = 12, fakeMode = "rand"),
            )
        val nonLeading =
            listOf(
                TcpChainStepModel(TcpChainStepKind.Split, "host+1"),
                TcpChainStepModel(TcpChainStepKind.SeqOverlap, "midsld", overlapSize = 12, fakeMode = "profile"),
            )

        assertThrows(IllegalArgumentException::class.java) {
            AppSettings.newBuilder().setStrategyChains(duplicate, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppSettings.newBuilder().setStrategyChains(nonLeading, emptyList())
        }
    }

    @Test
    fun `seqovl parser rejects invalid fake mode and overlap`() {
        val invalidFakeMode = parseStrategyChainDsl("[tcp]\nseqovl midsld fake=bogus")
        val zeroOverlap = parseStrategyChainDsl("[tcp]\nseqovl midsld overlap=0")
        val negativeOverlap = parseStrategyChainDsl("[tcp]\nseqovl midsld overlap=-1")

        assertTrue(invalidFakeMode.isFailure)
        assertFalse(invalidFakeMode.exceptionOrNull()?.message.isNullOrBlank())
        assertTrue(zeroOverlap.isFailure)
        assertFalse(zeroOverlap.exceptionOrNull()?.message.isNullOrBlank())
        assertTrue(negativeOverlap.isFailure)
        assertFalse(negativeOverlap.exceptionOrNull()?.message.isNullOrBlank())
    }

    @Test
    fun `seqovl proto defaults normalize overlap and fake mode`() {
        val settings =
            AppSettings
                .newBuilder()
                .addTcpChainSteps(
                    com.poyka.ripdpi.proto.StrategyTcpStep
                        .newBuilder()
                        .setKind("seqovl")
                        .setMarker("midsld")
                        .build(),
                ).build()

        val step = settings.effectiveTcpChainSteps().single()

        assertEquals(TcpChainStepKind.SeqOverlap, step.kind)
        assertEquals("midsld", step.marker)
        assertEquals(DefaultSeqOverlapSize, step.overlapSize)
        assertEquals(SeqOverlapFakeModeProfile, step.fakeMode)
        assertEquals("seqovl", primaryDesyncMethod(settings.effectiveTcpChainSteps()))
        assertEquals("tcp: seqovl(midsld overlap=12 fake=profile)", settings.effectiveChainSummary())
    }

    @Test
    fun `fake ordering dsl round trip preserves altorder and seqmode`() {
        val dsl =
            """
            [tcp]
            tlsrec extlen
            fakedsplit host+1 altorder=2 seqmode=sequential
            """.trimIndent()

        val parsed = parseStrategyChainDsl(dsl).getOrThrow()
        val step = parsed.tcpSteps.last()

        assertEquals(TcpChainStepKind.FakeSplit, step.kind)
        assertEquals(FakeOrderInterleaveRealFirst, step.fakeOrder)
        assertEquals(FakeSeqModeSequential, step.fakeSeqMode)
        assertEquals(dsl, formatStrategyChainDsl(parsed.tcpSteps, parsed.udpSteps))
        assertEquals(
            "tcp: tlsrec(extlen) -> fakedsplit(host+1 altorder=2 seqmode=sequential)",
            formatChainSummary(parsed.tcpSteps, parsed.udpSteps),
        )
    }

    @Test
    fun `fake ordering proto defaults normalize unsupported step fields away`() {
        val settings =
            AppSettings
                .newBuilder()
                .addTcpChainSteps(
                    com.poyka.ripdpi.proto.StrategyTcpStep
                        .newBuilder()
                        .setKind("split")
                        .setMarker("host+1")
                        .setFakeOrder("3")
                        .setFakeSeqMode("sequential")
                        .build(),
                ).build()

        val step = settings.effectiveTcpChainSteps().single()

        assertEquals(TcpChainStepKind.Split, step.kind)
        assertEquals(FakeOrderDefault, step.fakeOrder)
        assertEquals(FakeSeqModeDuplicate, step.fakeSeqMode)
    }

    @Test
    fun `hostfake validation rejects non default order without midhost`() {
        val steps =
            listOf(
                TcpChainStepModel(
                    kind = TcpChainStepKind.HostFake,
                    marker = "endhost+8",
                    fakeOrder = FakeOrderAllFakesFirst,
                ),
            )

        assertThrows(IllegalArgumentException::class.java) {
            AppSettings.newBuilder().setStrategyChains(steps, emptyList())
        }
    }

    @Test
    fun `hostfake allows sequential mode without midhost`() {
        val steps =
            listOf(
                TcpChainStepModel(
                    kind = TcpChainStepKind.HostFake,
                    marker = "endhost+8",
                    fakeSeqMode = FakeSeqModeSequential,
                ),
            )

        val settings = AppSettings.newBuilder().setStrategyChains(steps, emptyList()).build()

        assertEquals(FakeSeqModeSequential, settings.effectiveTcpChainSteps().single().fakeSeqMode)
    }

    @Test
    fun `multidisorder dsl round trip preserves grouped markers and summary`() {
        val dsl =
            """
            [tcp]
            tlsrec extlen
            multidisorder sniext
            multidisorder host
            """.trimIndent()

        val parsed = parseStrategyChainDsl(dsl).getOrThrow()

        assertEquals(
            listOf(
                TcpChainStepModel(TcpChainStepKind.TlsRec, "extlen"),
                TcpChainStepModel(TcpChainStepKind.MultiDisorder, "sniext"),
                TcpChainStepModel(TcpChainStepKind.MultiDisorder, "host"),
            ),
            parsed.tcpSteps,
        )
        assertEquals(dsl, formatStrategyChainDsl(parsed.tcpSteps, parsed.udpSteps))
        assertEquals(
            "tcp: tlsrec(extlen) -> multidisorder(sniext) -> multidisorder(host)",
            formatChainSummary(parsed.tcpSteps, parsed.udpSteps),
        )
        assertEquals("multidisorder", primaryDesyncMethod(parsed.tcpSteps))
    }

    @Test
    fun `multidisorder validation rejects singleton and mixed send families`() {
        val singleton =
            listOf(
                TcpChainStepModel(TcpChainStepKind.MultiDisorder, "host+1"),
            )
        val mixed =
            listOf(
                TcpChainStepModel(TcpChainStepKind.TlsRec, "extlen"),
                TcpChainStepModel(TcpChainStepKind.MultiDisorder, "sniext"),
                TcpChainStepModel(TcpChainStepKind.Split, "host"),
            )

        assertThrows(IllegalArgumentException::class.java) {
            AppSettings.newBuilder().setStrategyChains(singleton, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppSettings.newBuilder().setStrategyChains(mixed, emptyList())
        }
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
    fun `empty settings expose no effective chains`() {
        val settings = AppSettings.newBuilder().build()

        assertTrue(settings.effectiveTcpChainSteps().isEmpty())
        assertTrue(settings.effectiveUdpChainSteps().isEmpty())
        assertEquals("none", primaryDesyncMethod(settings.effectiveTcpChainSteps()))
        assertEquals(CanonicalDefaultSplitMarker, settings.effectiveSplitMarker())
        assertEquals(DefaultTlsRecordMarker, settings.effectiveTlsRecordMarker())
    }

    @Test
    fun `setting strategy chains stores canonical steps and summaries`() {
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

        assertEquals("split", primaryDesyncMethod(settings.effectiveTcpChainSteps()))
        assertEquals("host+2", settings.effectiveSplitMarker())
        assertEquals("extlen", settings.effectiveTlsRecordMarker())
        assertEquals(listOf(UdpChainStepModel(count = 4)), settings.effectiveUdpChainSteps())
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
    fun `ech markers round trip through dsl settings and summary`() {
        val dsl =
            """
            [tcp]
            tlsrec echext
            split echext+4
            """.trimIndent()

        val parsed = parseStrategyChainDsl(dsl).getOrThrow()
        val settings =
            AppSettings
                .newBuilder()
                .setStrategyChains(parsed.tcpSteps, parsed.udpSteps)
                .build()

        assertEquals(
            listOf(
                TcpChainStepModel(TcpChainStepKind.TlsRec, "echext"),
                TcpChainStepModel(TcpChainStepKind.Split, "echext+4"),
            ),
            parsed.tcpSteps,
        )
        assertEquals(dsl, formatStrategyChainDsl(parsed.tcpSteps, parsed.udpSteps))
        assertEquals("echext+4", settings.effectiveSplitMarker())
        assertEquals("echext", settings.effectiveTlsRecordMarker())
        assertEquals("tcp: tlsrec(echext) -> split(echext+4)", settings.effectiveChainSummary())
    }

    @Test
    fun `hostfake strategy chains preserve proto step fields`() {
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

        assertEquals("fake", primaryDesyncMethod(settings.effectiveTcpChainSteps()))
        assertEquals("endhost+4", settings.effectiveSplitMarker())
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
    fun `fake approximation steps round trip through dsl and canonical summary`() {
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
        assertEquals("fake", primaryDesyncMethod(settings.effectiveTcpChainSteps()))
        assertEquals(AdaptiveMarkerHost, settings.effectiveSplitMarker())
    }

    @Test
    fun `fake approximation steps must be terminal in tcp chain`() {
        val fakedsplitResult = parseStrategyChainDsl("[tcp]\nfakedsplit host+1\nsplit endhost")
        val fakeddisorderResult = parseStrategyChainDsl("[tcp]\nfakeddisorder host+1\nfake endhost")

        assertTrue(fakedsplitResult.isFailure)
        assertTrue(fakeddisorderResult.isFailure)
    }

    @Test
    fun `tlsrandrec dsl round trip preserves fragment options and summary`() {
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

        assertEquals("split", primaryDesyncMethod(settings.effectiveTcpChainSteps()))
        assertEquals("sniext+4", settings.effectiveTlsRecordMarker())
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
}
