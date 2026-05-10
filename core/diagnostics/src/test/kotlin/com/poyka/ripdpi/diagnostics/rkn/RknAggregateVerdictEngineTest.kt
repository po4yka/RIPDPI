package com.poyka.ripdpi.diagnostics.rkn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RknAggregateVerdictEngineTest {
    @Test
    fun `whitelist below half returns inconclusive control down`() {
        val verdict =
            RknAggregateVerdictEngine.aggregate(
                whitelist = checks(ok = 5, down = 16),
                blacklist = checks(blockedHigh = 15),
            )

        assertTrue(verdict is RknAggregateVerdict.InconclusiveControlDown)
    }

    @Test
    fun `all blacklist timeout returns inconclusive all timeout`() {
        val verdict =
            RknAggregateVerdictEngine.aggregate(
                whitelist = checks(ok = 21),
                blacklist = checks(timeout = 15),
            )

        assertTrue(verdict is RknAggregateVerdict.InconclusiveAllTimeout)
    }

    @Test
    fun `blacklist all ok returns not blocked or vpn`() {
        val verdict =
            RknAggregateVerdictEngine.aggregate(
                whitelist = checks(ok = 21),
                blacklist = checks(ok = 15),
            )

        assertTrue(verdict is RknAggregateVerdict.NotBlockedOrVpn)
    }

    @Test
    fun `seventy percent blocked with majority high returns high confidence`() {
        val verdict =
            RknAggregateVerdictEngine.aggregate(
                whitelist = checks(ok = 21),
                blacklist = checks(blockedHigh = 8, blockedMedium = 3, ok = 4),
            )

        assertTrue(verdict is RknAggregateVerdict.InBlockedZoneHigh)
    }

    @Test
    fun `seventy percent blocked mostly medium returns medium confidence`() {
        val verdict =
            RknAggregateVerdictEngine.aggregate(
                whitelist = checks(ok = 21),
                blacklist = checks(blockedMedium = 11, ok = 4),
            )

        assertTrue(verdict is RknAggregateVerdict.InBlockedZoneMedium)
    }

    @Test
    fun `partial blocks returns partial`() {
        val verdict =
            RknAggregateVerdictEngine.aggregate(
                whitelist = checks(ok = 21),
                blacklist = checks(blockedHigh = 5, ok = 10),
            )

        assertTrue(verdict is RknAggregateVerdict.PartialBlocks)
    }

    @Test
    fun `confidence note quotes high count`() {
        val verdict =
            RknAggregateVerdictEngine.aggregate(
                whitelist = checks(ok = 21),
                blacklist = checks(blockedHigh = 8, blockedMedium = 3, ok = 4),
            )

        assertTrue(verdict.confidenceNote.contains("8/15"))
    }

    @Test
    fun `block type tally counts per verdict`() {
        val tally =
            RknAggregateVerdictEngine.blockTypes(
                listOf(
                    check(RknVerdict.DNS_BLOCK),
                    check(RknVerdict.DNS_BLOCK),
                    check(RknVerdict.DNS_BLOCK),
                    check(RknVerdict.TLS_BLOCK),
                    check(RknVerdict.TLS_BLOCK),
                    check(RknVerdict.TLS_BLOCK),
                    check(RknVerdict.TLS_BLOCK),
                    check(RknVerdict.TLS_BLOCK),
                    check(RknVerdict.HTTP_STUB),
                    check(RknVerdict.HTTP_STUB),
                ),
            )

        assertEquals(3, tally[RknVerdict.DNS_BLOCK])
        assertEquals(5, tally[RknVerdict.TLS_BLOCK])
        assertEquals(2, tally[RknVerdict.HTTP_STUB])
    }

    @Test
    fun `empty whitelist skips control down gate`() {
        val verdict =
            RknAggregateVerdictEngine.aggregate(
                whitelist = emptyList(),
                blacklist = checks(blockedHigh = 8, blockedMedium = 3, ok = 4),
            )

        assertTrue(verdict is RknAggregateVerdict.InBlockedZoneHigh)
    }

    @Test
    fun `verdict label high uses x symbol`() {
        val label = RknVerdictLabel.format(RknVerdict.DNS_BLOCK, RknConfidence.HIGH)

        assertEquals("✗", label.symbol)
        assertEquals("DNS", label.text)
    }

    @Test
    fun `verdict label medium uses likely prefix`() {
        val label = RknVerdictLabel.format(RknVerdict.TLS_BLOCK, RknConfidence.MEDIUM)

        assertEquals("~", label.symbol)
        assertEquals("LIKELY TLS DPI", label.text)
    }

    @Test
    fun `boundary seventy percent inclusive`() {
        val verdict =
            RknAggregateVerdictEngine.aggregate(
                whitelist = checks(ok = 21),
                blacklist = checks(blockedMedium = 7, ok = 3),
            )

        assertTrue(verdict is RknAggregateVerdict.InBlockedZoneMedium)
    }

    private fun checks(
        ok: Int = 0,
        down: Int = 0,
        timeout: Int = 0,
        blockedHigh: Int = 0,
        blockedMedium: Int = 0,
    ): List<RknCheckResult> =
        buildList {
            repeat(ok) { add(check(RknVerdict.OK, RknConfidence.HIGH)) }
            repeat(down) { add(check(RknVerdict.DOWN, RknConfidence.LOW)) }
            repeat(timeout) { add(check(RknVerdict.TIMEOUT, RknConfidence.LOW)) }
            repeat(blockedHigh) { add(check(RknVerdict.DNS_BLOCK, RknConfidence.HIGH)) }
            repeat(blockedMedium) { add(check(RknVerdict.TLS_BLOCK, RknConfidence.MEDIUM)) }
        }

    private fun check(
        verdict: RknVerdict,
        confidence: RknConfidence = RknConfidence.HIGH,
    ): RknCheckResult =
        RknCheckResult(
            name = verdict.name,
            url = "https://example.test/",
            verdict = verdict,
            confidence = confidence,
        )
}
