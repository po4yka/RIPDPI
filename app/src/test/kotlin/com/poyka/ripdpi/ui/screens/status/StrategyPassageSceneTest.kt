package com.poyka.ripdpi.ui.screens.status

import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Expected orders are written from the native planner (`plan_tcp`, `plan_udp`, `tcp_fake_family`),
 * not from the compiler: a low-TTL piece dies on the path and the kernel resends the real bytes last.
 * Schematic positions: host starts at 30 and ends at 48; `host+1` lands at 33.
 */
class StrategyPassageSceneTest {
    private fun real(
        start: Int,
        end: Int,
    ) = PassagePiece(PassagePieceKind.Real, start, end)

    private fun decoy(
        start: Int,
        end: Int,
    ) = PassagePiece(PassagePieceKind.Decoy, start, end)

    private fun resend(
        start: Int,
        end: Int,
    ) = PassagePiece(PassagePieceKind.Retransmit, start, end)

    private fun fragment(
        start: Int,
        end: Int,
    ) = PassagePiece(PassagePieceKind.IpFragment, start, end)

    private fun tcp(
        kind: TcpChainStepKind,
        marker: String = "host+1",
        midhostMarker: String = "",
        fragmentCount: Int = 0,
    ) = TcpChainStepModel(kind, marker, midhostMarker = midhostMarker, fragmentCount = fragmentCount)

    private fun udp(
        kind: UdpChainStepKind,
        count: Int = 1,
    ) = UdpChainStepModel(count, kind)

    private fun tcpPieces(vararg steps: TcpChainStepModel) =
        requireNotNull(compileStrategyPassageScene(steps.toList(), emptyList()).tcp).pieces

    private fun udpPieces(vararg steps: UdpChainStepModel) =
        requireNotNull(compileStrategyPassageScene(emptyList(), steps.toList()).udp).pieces

    @Test
    fun `split sends both parts in order`() {
        assertEquals(listOf(real(0, 33), real(33, 100)), tcpPieces(tcp(TcpChainStepKind.Split)))
    }

    @Test
    fun `each split cuts the remainder`() {
        assertEquals(
            listOf(real(0, 6), real(6, 33), real(33, 48), real(48, 100)),
            tcpPieces(
                tcp(TcpChainStepKind.Split, "10"),
                tcp(TcpChainStepKind.Split),
                tcp(TcpChainStepKind.Split, "endhost"),
            ),
        )
    }

    @Test
    fun `a marker behind the cut adds no piece`() {
        assertEquals(
            listOf(real(0, 48), real(48, 100)),
            tcpPieces(tcp(TcpChainStepKind.Split, "endhost"), tcp(TcpChainStepKind.Split, "host")),
        )
    }

    @Test
    fun `adaptive and repeated markers use their base position`() {
        val expected = listOf(real(0, 30), real(30, 100))
        assertEquals(expected, tcpPieces(tcp(TcpChainStepKind.Split, "auto(host)")))
        assertEquals(expected, tcpPieces(tcp(TcpChainStepKind.Split, "auto(balanced)")))
        assertEquals(expected, tcpPieces(tcp(TcpChainStepKind.Split, "host:2:1")))
    }

    @Test
    fun `syndata carries the first part in the SYN`() {
        assertEquals(
            listOf(PassagePiece(PassagePieceKind.Real, 0, 33, inSyn = true), real(33, 100)),
            tcpPieces(tcp(TcpChainStepKind.SynData)),
        )
    }

    @Test
    fun `disorder loses the first part and resends it after the remainder`() {
        assertEquals(
            listOf(PassagePiece(PassagePieceKind.ShortTtl, 0, 33), real(33, 100), resend(0, 33)),
            tcpPieces(tcp(TcpChainStepKind.Disorder)),
        )
    }

    @Test
    fun `multidisorder sends segments in reverse order`() {
        assertEquals(
            listOf(real(48, 100), real(33, 48), real(0, 33)),
            tcpPieces(tcp(TcpChainStepKind.MultiDisorder), tcp(TcpChainStepKind.MultiDisorder, "endhost")),
        )
    }

    @Test
    fun `fake sends a decoy and resends the real bytes after the remainder`() {
        assertEquals(
            listOf(decoy(0, 33), real(33, 100), resend(0, 33)),
            tcpPieces(tcp(TcpChainStepKind.Fake)),
        )
    }

    @Test
    fun `fakedsplit covers the whole payload with decoys`() {
        assertEquals(
            listOf(decoy(0, 33), decoy(33, 100), resend(0, 33), resend(33, 100)),
            tcpPieces(tcp(TcpChainStepKind.FakeSplit)),
        )
    }

    @Test
    fun `fakeddisorder ends its first decoy before DPI and resends oldest bytes first`() {
        assertEquals(
            listOf(
                PassagePiece(PassagePieceKind.Decoy, 0, 33, endsBeforeDpi = true),
                decoy(33, 100),
                resend(0, 33),
                resend(33, 100),
            ),
            tcpPieces(tcp(TcpChainStepKind.FakeDisorder)),
        )
    }

    @Test
    fun `hostfake wraps the real host in two decoys`() {
        assertEquals(
            listOf(real(0, 30), decoy(30, 48), real(30, 48), decoy(30, 48), real(48, 100)),
            tcpPieces(tcp(TcpChainStepKind.HostFake, "endhost")),
        )
    }

    @Test
    fun `hostfake splits the real host at midhost`() {
        assertEquals(
            listOf(real(0, 30), decoy(30, 48), real(30, 39), real(39, 48), decoy(30, 48), real(48, 100)),
            tcpPieces(tcp(TcpChainStepKind.HostFake, "endhost", midhostMarker = "midsld")),
        )
    }

    @Test
    fun `hostfake without the whole host in range is a plain split`() {
        assertEquals(listOf(real(0, 33), real(33, 100)), tcpPieces(tcp(TcpChainStepKind.HostFake)))
    }

    @Test
    fun `oob marks the urgent byte on the first part`() {
        assertEquals(
            listOf(PassagePiece(PassagePieceKind.Real, 0, 33, hasUrgentByte = true), real(33, 100)),
            tcpPieces(tcp(TcpChainStepKind.Oob)),
        )
    }

    @Test
    fun `disoob sends the urgent byte with a short TTL and resends the bytes`() {
        assertEquals(
            listOf(PassagePiece(PassagePieceKind.ShortTtl, 0, 33, hasUrgentByte = true), real(33, 100), resend(0, 33)),
            tcpPieces(tcp(TcpChainStepKind.Disoob)),
        )
    }

    @Test
    fun `fake reset precedes the chunk without consuming bytes`() {
        assertEquals(
            listOf(PassagePiece(PassagePieceKind.FakeReset, 0, 0), real(0, 33), real(33, 100)),
            tcpPieces(tcp(TcpChainStepKind.FakeRst)),
        )
    }

    @Test
    fun `seqovl adds a fake prefix and ends the plan`() {
        assertEquals(
            listOf(PassagePiece(PassagePieceKind.Real, 0, 33, hasFakePrefix = true), real(33, 100)),
            tcpPieces(tcp(TcpChainStepKind.SeqOverlap)),
        )
    }

    @Test
    fun `ipfrag2 sends two fragments and ends the plan`() {
        assertEquals(
            listOf(fragment(0, 33), fragment(33, 100)),
            tcpPieces(tcp(TcpChainStepKind.IpFrag2)),
        )
    }

    @Test
    fun `tls prelude adds boundaries without adding pieces`() {
        val lane =
            compileStrategyPassageScene(
                listOf(tcp(TcpChainStepKind.TlsRec, "midsld"), tcp(TcpChainStepKind.Split)),
                emptyList(),
            ).tcp
        assertEquals(listOf(39), lane?.recordBoundaries)
        assertEquals(listOf(real(0, 33), real(33, 100)), lane?.pieces)
    }

    @Test
    fun `tlsrandrec spreads its fragment count from the marker`() {
        val lane =
            compileStrategyPassageScene(
                listOf(tcp(TcpChainStepKind.TlsRandRec, "host", fragmentCount = 3)),
                emptyList(),
            ).tcp
        assertEquals(listOf(30, 53, 76), lane?.recordBoundaries)
        assertEquals(listOf(real(0, 100)), lane?.pieces)
    }

    @Test
    fun `an empty chain passes one whole packet and names no family`() {
        val scene = compileStrategyPassageScene(emptyList(), emptyList())
        assertEquals(listOf(real(0, 100)), scene.tcp?.pieces)
        assertNull(scene.udp)
        assertEquals(emptyList<PassageFamily>(), scene.families)
    }

    @Test
    fun `udp only chain has no tcp lane`() {
        assertNull(compileStrategyPassageScene(emptyList(), listOf(udp(UdpChainStepKind.FakeBurst))).tcp)
    }

    @Test
    fun `udp decoys precede the real packet and large counts collapse`() {
        val copy = { index: Int, extra: Int ->
            PassagePiece(
                PassagePieceKind.Decoy,
                0,
                100,
                quicStep = UdpChainStepKind.FakeBurst,
                copyIndex = index,
                extraCopies = extra,
            )
        }
        assertEquals(
            listOf(copy(0, 0), copy(1, 0), copy(2, 0), copy(3, 4), real(0, 100)),
            udpPieces(udp(UdpChainStepKind.FakeBurst, 8)),
        )
    }

    @Test
    fun `udp step with no copies is skipped`() {
        assertEquals(listOf(real(0, 100)), udpPieces(udp(UdpChainStepKind.FakeBurst, 0)))
    }

    @Test
    fun `realistic multi initial sends at least two copies`() {
        assertEquals(3, udpPieces(udp(UdpChainStepKind.QuicMultiInitialRealistic, 1)).size)
    }

    @Test
    fun `udp fragmentation replaces the real packet`() {
        assertEquals(
            listOf(fragment(0, 39), fragment(39, 100)),
            udpPieces(udp(UdpChainStepKind.IpFrag2Udp)),
        )
    }

    @Test
    fun `families follow the chain and do not repeat`() {
        assertEquals(
            listOf(PassageFamily.TlsRecords, PassageFamily.Cut, PassageFamily.QuicDecoyBurst),
            compileStrategyPassageScene(
                listOf(
                    tcp(TcpChainStepKind.TlsRec),
                    tcp(TcpChainStepKind.Split, "10"),
                    tcp(TcpChainStepKind.Split),
                ),
                listOf(udp(UdpChainStepKind.FakeBurst), udp(UdpChainStepKind.DummyPrepend)),
            ).families,
        )
    }

    @Test
    fun `every engine step has a family`() {
        val expected =
            mapOf(
                TcpChainStepKind.Split to PassageFamily.Cut,
                TcpChainStepKind.SynData to PassageFamily.Cut,
                TcpChainStepKind.Disorder to PassageFamily.LaggingPart,
                TcpChainStepKind.MultiDisorder to PassageFamily.LaggingPart,
                TcpChainStepKind.Fake to PassageFamily.Decoy,
                TcpChainStepKind.FakeSplit to PassageFamily.Decoy,
                TcpChainStepKind.FakeDisorder to PassageFamily.Decoy,
                TcpChainStepKind.HostFake to PassageFamily.HostMask,
                TcpChainStepKind.TlsRec to PassageFamily.TlsRecords,
                TcpChainStepKind.TlsRandRec to PassageFamily.TlsRecords,
                TcpChainStepKind.Oob to PassageFamily.UrgentByte,
                TcpChainStepKind.Disoob to PassageFamily.UrgentByte,
                TcpChainStepKind.IpFrag2 to PassageFamily.IpFragments,
                TcpChainStepKind.SeqOverlap to PassageFamily.Overlap,
                TcpChainStepKind.FakeRst to PassageFamily.FakeReset,
            )
        assertEquals(TcpChainStepKind.entries.toSet(), expected.keys)
        expected.forEach { (kind, family) ->
            assertEquals(
                listOf(family),
                compileStrategyPassageScene(listOf(tcp(kind, "endhost")), emptyList()).families,
            )
        }
        UdpChainStepKind.entries.forEach { kind ->
            val expectedFamily =
                when (kind) {
                    UdpChainStepKind.QuicSniSplit, UdpChainStepKind.QuicCryptoSplit -> PassageFamily.QuicSplitCopy

                    UdpChainStepKind.QuicFakeVersion,
                    UdpChainStepKind.QuicVersionNegotiationDecoy,
                    -> PassageFamily.QuicVersion

                    UdpChainStepKind.IpFrag2Udp -> PassageFamily.IpFragments

                    else -> PassageFamily.QuicDecoyBurst
                }
            assertEquals(listOf(expectedFamily), compileStrategyPassageScene(emptyList(), listOf(udp(kind))).families)
        }
    }

    @Test
    fun `a relay or custom rules leave no scene`() {
        assertNotNull(strategyPassageFor(activeSettings().build()))
        assertNull(
            strategyPassageFor(
                activeSettings().setRelayEnabled(true).setRelayKind(RelayKindVlessReality).build(),
            ),
        )
        assertNull(strategyPassageFor(activeSettings().setEnableCmdSettings(true).build()))
        assertNull(strategyPassageFor(activeSettings().setStrategyChainYaml("rules: []").build()))
    }

    @Test
    fun `no TCP protocol leaves no scene`() {
        assertNull(strategyPassageFor(activeSettings().setDesyncHttp(false).setDesyncHttps(false).build()))
        assertNotNull(strategyPassageFor(activeSettings().setDesyncHttp(false).build()))
    }

    @Test
    fun `udp desync off drops the udp lane`() {
        val settings = activeSettings().setStrategyChains(emptyList(), listOf(udp(UdpChainStepKind.FakeBurst)))
        assertNotNull(strategyPassageFor(settings.setDesyncUdp(true).build())?.udp)
        assertNull(strategyPassageFor(settings.setDesyncUdp(false).build())?.udp)
    }

    @Test
    fun `hostfake without the whole host is named a cut`() {
        assertEquals(
            listOf(PassageFamily.Cut),
            compileStrategyPassageScene(listOf(tcp(TcpChainStepKind.HostFake)), emptyList()).families,
        )
    }

    @Test
    fun `flights leave in plan order and a resend lands last`() {
        val lane = requireNotNull(compileStrategyPassageScene(listOf(tcp(TcpChainStepKind.Disorder)), emptyList()).tcp)
        // Start 120 ms, spacing 220 ms, flight 480 ms: pieces land at 600, 820, and 1040 ms.
        assertEquals(emptyList<PassagePiece>(), lane.landedPieces(599f))
        assertEquals(listOf(real(33, 100)), lane.landedPieces(820f))
        assertEquals(listOf(real(33, 100), resend(0, 33)), lane.landedPieces(1040f))
        assertEquals(1040, lane.settleStartMillis)
    }

    @Test
    fun `decoys never land`() {
        val lane = requireNotNull(compileStrategyPassageScene(listOf(tcp(TcpChainStepKind.FakeSplit)), emptyList()).tcp)
        assertEquals(listOf(resend(0, 33), resend(33, 100)), lane.landedPieces(Float.MAX_VALUE))
    }

    @Test
    fun `scene duration follows the longest lane`() {
        val scene =
            compileStrategyPassageScene(
                listOf(tcp(TcpChainStepKind.Split)),
                listOf(udp(UdpChainStepKind.FakeBurst, 4)),
            )
        // Five UDP pieces: 120 + 4 * 220 + 480, then 260 ms to settle.
        assertEquals(1740, scene.durationMillis)
    }

    @Test
    fun `without raw packets the lowered steps draw plain cuts`() {
        val noRaw = { step: TcpChainStepModel ->
            compileStrategyPassageScene(listOf(step), emptyList(), rawPackets = false)
        }

        val hostfake = noRaw(tcp(TcpChainStepKind.HostFake, "endhost+8"))
        val hostfakePieces = hostfake.tcp!!.pieces
        assertEquals(listOf(PassageFamily.Cut), hostfake.families)
        assertEquals(listOf(real(0, PassageHostStart), real(PassageHostStart, PassageHostEnd)), hostfakePieces.take(2))
        assertEquals(setOf(PassagePieceKind.Real), hostfakePieces.map { it.kind }.toSet())

        listOf(TcpChainStepKind.SeqOverlap, TcpChainStepKind.FakeRst).forEach { kind ->
            val scene = noRaw(tcp(kind, "host+1"))
            assertEquals(listOf(PassageFamily.Cut), scene.families)
            assertEquals(noRaw(tcp(TcpChainStepKind.Split, "host+1")).tcp, scene.tcp)
        }
    }

    @Test
    fun `only root mode shows raw packets`() {
        val settings =
            activeSettings().setStrategyChains(listOf(tcp(TcpChainStepKind.HostFake, "endhost+8")), emptyList())
        val kinds = { builder: AppSettings.Builder ->
            strategyPassageFor(builder.build())
                ?.tcp
                ?.pieces
                ?.map { it.kind }
                ?.toSet()
        }

        assertEquals(setOf(PassagePieceKind.Real), kinds(settings.clone().setRootModeEnabled(false)))
        assertEquals(
            setOf(PassagePieceKind.Real, PassagePieceKind.Decoy),
            kinds(settings.clone().setRootModeEnabled(true)),
        )
    }

    @Test
    fun `a chain the engine refuses without raw packets leaves no scene`() {
        val settings = { tcpSteps: List<TcpChainStepModel>, udpSteps: List<UdpChainStepModel> ->
            activeSettings().setDesyncUdp(true).setStrategyChains(tcpSteps, udpSteps)
        }
        val multiDisorder = listOf("host+1", "endhost").map { tcp(TcpChainStepKind.MultiDisorder, it) }
        val udpFragments = UdpChainStepModel(1, UdpChainStepKind.IpFrag2Udp, splitBytes = 8)
        val blocked =
            listOf(
                settings(multiDisorder, emptyList()),
                settings(listOf(tcp(TcpChainStepKind.IpFrag2)), emptyList()),
                settings(listOf(tcp(TcpChainStepKind.Split)), listOf(udpFragments)),
            )

        blocked.forEach { builder ->
            assertNull(strategyPassageFor(builder.clone().build()))
            assertNotNull(strategyPassageFor(builder.clone().setRootModeEnabled(true).build()))
        }
    }

    private fun activeSettings() = AppSettings.newBuilder().setDesyncHttp(true).setDesyncHttps(true)
}
