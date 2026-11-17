package com.poyka.ripdpi.ui.screens.status

import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.data.UdpChainStepModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategySchemeTest {
    private fun lane(vararg steps: TcpChainStepModel): PassageLane =
        requireNotNull(compileStrategyPassageScene(steps.toList(), emptyList()).tcp)

    private fun step(
        kind: TcpChainStepKind,
        marker: String = "host+1",
    ) = TcpChainStepModel(kind, marker)

    @Test
    fun `a cut one byte into the host divides the sample name after its first letter`() {
        val readout = schemeReadout(lane(step(TcpChainStepKind.Split)))

        assertEquals(listOf("s", "ite.test"), readout.chunks.map { it.text })
        assertEquals(SchemeVerdict.NoMatch, readout.verdict)
    }

    @Test
    fun `a packet without changes shows the filter the whole name`() {
        val readout = schemeReadout(lane())

        assertEquals(listOf("site.test"), readout.chunks.map { it.text })
        assertEquals(SchemeVerdict.WholeName, readout.verdict)
    }

    @Test
    fun `the filter stops at a decoy and does not read the packets after it`() {
        val readout = schemeReadout(lane(step(TcpChainStepKind.Fake)))

        assertEquals(listOf("g"), readout.chunks.map { it.text })
        assertTrue(readout.chunks.single().decoy)
        assertEquals(SchemeVerdict.StopsLooking, readout.verdict)
    }

    @Test
    fun `a record border inside the host breaks the name that the filter reads`() {
        val readout = schemeReadout(lane(step(TcpChainStepKind.TlsRec, "sld")))

        assertEquals(listOf("si|te.test"), readout.chunks.map { it.text })
        assertEquals(SchemeVerdict.NoMatch, readout.verdict)
    }

    @Test
    fun `a grid has one cell per host letter and one per span outside the host`() {
        val grid = SchemeGrid(lane(step(TcpChainStepKind.Split, "extlen"), step(TcpChainStepKind.Split, "host+1")))

        // Two spans before the host, nine letters, one span after it.
        assertEquals(12, grid.cellCount)
        assertEquals(1, grid.cellAt(14))
        assertEquals(2, grid.cellAt(PassageHostStart))
        assertEquals(3, grid.cellAt(33))
        assertEquals(12, grid.cellAt(PassagePayloadEnd))
    }

    @Test
    fun `bytes leave the device one time, and a resend of lost bytes appears in its own line`() {
        val disorder = lane(step(TcpChainStepKind.Disorder))
        val fake = lane(step(TcpChainStepKind.Fake))

        // Disorder: the short TTL part takes the bytes, so its resend does not.
        assertEquals(listOf(true, true, false), schemeRows(disorder, SchemeGrid(disorder)).map { it.staged })
        // Fake: the decoy is a copy, so the resend is the first to take the real bytes.
        assertEquals(listOf(false, true, true), schemeRows(fake, SchemeGrid(fake)).map { it.staged })
    }

    @Test
    fun `a chain that needs raw packets is marked as lowered only without them`() {
        val steps = listOf(step(TcpChainStepKind.SeqOverlap))

        assertTrue(compileStrategyPassageScene(steps, emptyList(), rawPackets = false).lowered)
        assertFalse(compileStrategyPassageScene(steps, emptyList(), rawPackets = true).lowered)
        assertFalse(
            compileStrategyPassageScene(listOf(step(TcpChainStepKind.Split)), emptyList(), rawPackets = false).lowered,
        )
    }

    @Test
    fun `a short TTL part ends before the filter, so the filter reads the late part first`() {
        val readout = schemeReadout(lane(step(TcpChainStepKind.Disorder)))

        assertEquals(listOf("ite.test", "s"), readout.chunks.map { it.text })
        assertEquals(SchemeVerdict.NoMatch, readout.verdict)
    }

    @Test
    fun `a fake reset ends the inspection before any real byte`() {
        val readout = schemeReadout(lane(step(TcpChainStepKind.FakeRst)))

        assertEquals(listOf("RST"), readout.chunks.map { it.text })
        assertEquals(SchemeVerdict.StopsLooking, readout.verdict)
    }

    @Test
    fun `a QUIC split copy carries the real name in two parts`() {
        val udp = listOf(UdpChainStepModel(1, UdpChainStepKind.QuicSniSplit))
        val quic = requireNotNull(compileStrategyPassageScene(emptyList(), udp).udp)

        val copy = schemeReadout(quic).chunks.single().text

        // The cut is in the middle of the name, so the copy is the real name with one border in it.
        assertEquals(SchemeSampleHost, copy.replace("|", ""))
        assertEquals(SchemeSampleHost.length + 1, copy.length)
    }
}
