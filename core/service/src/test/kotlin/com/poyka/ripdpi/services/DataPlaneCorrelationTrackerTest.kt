package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ProxyForwardingEvidence
import com.poyka.ripdpi.core.TunForwardingEvidence
import com.poyka.ripdpi.data.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataPlaneCorrelationTrackerTest {
    @Test
    fun unavailablePollDoesNotDiscardCurrentGenerationEvidence() {
        val tracker = DataPlaneCorrelationTracker(Mode.VPN, TestServiceClock(now = 42L))

        tracker.observe(
            proxyEvidence = ProxyForwardingEvidence(upstreamApplicationBytes = 100),
            tunEvidence = TunForwardingEvidence(),
            tunSupport = TunEvidenceSupport.Supported,
        )
        tracker.observe(
            proxyEvidence =
                ProxyForwardingEvidence(
                    upstreamApplicationBytes = 200,
                    firstUpstreamApplicationForwardedAt = 1_000,
                    lastUpstreamApplicationForwardedAt = 2_000,
                ),
            tunEvidence = TunForwardingEvidence(),
            tunSupport = TunEvidenceSupport.Supported,
        )
        tracker.finalEvent()
        val finalBeforeZero = tracker.events().last()

        tracker.observe(proxyEvidence = null, tunEvidence = null, tunSupport = TunEvidenceSupport.Unavailable)
        tracker.finalEvent()

        assertTrue(finalBeforeZero.message.contains("final=true"))
        assertTrue(finalBeforeZero.message.contains("proxy_application_bytes=200"))
        assertTrue(finalBeforeZero.message.contains("last_proxy_application_at=2000"))
        assertFalse(tracker.events().any { it.kind == "data_plane_counter_reset" })
        assertEquals(finalBeforeZero, tracker.events().last())
    }

    @Test
    fun allZeroProxyCountersStartNewGenerationBeforeFinalSeal() {
        val tracker = DataPlaneCorrelationTracker(Mode.Proxy, TestServiceClock(now = 42L))

        tracker.observe(
            proxyEvidence = ProxyForwardingEvidence(upstreamApplicationBytes = 200),
            tunEvidence = null,
            tunSupport = TunEvidenceSupport.Unsupported,
        )
        tracker.observe(
            proxyEvidence = ProxyForwardingEvidence.Empty,
            tunEvidence = null,
            tunSupport = TunEvidenceSupport.Unsupported,
            proxyGenerationAuthoritative = true,
        )
        tracker.finalEvent()

        val events = tracker.events()
        assertTrue(events.any { it.kind == "data_plane_counter_reset" && it.message.contains("generation=2") })
        val finalEvent = events.last()
        assertEquals("data_plane_final", finalEvent.kind)
        assertTrue(finalEvent.message.contains("state=no_flow"))
        assertTrue(finalEvent.message.contains("generation=2"))
        assertTrue(finalEvent.message.contains("proxy_application_bytes=0"))
        assertFalse(finalEvent.message.contains("proxy_application_bytes=200"))
    }

    @Test
    fun allZeroTunCountersStartNewGenerationBeforeFinalSeal() {
        val tracker = DataPlaneCorrelationTracker(Mode.VPN, TestServiceClock(now = 42L))

        tracker.observe(
            proxyEvidence = ProxyForwardingEvidence(upstreamApplicationBytes = 200),
            tunEvidence = TunForwardingEvidence(tunWriteBytes = 100),
            tunSupport = TunEvidenceSupport.Supported,
        )
        tracker.observe(
            proxyEvidence = ProxyForwardingEvidence(upstreamApplicationBytes = 200),
            tunEvidence = TunForwardingEvidence(),
            tunSupport = TunEvidenceSupport.Supported,
            tunGenerationAuthoritative = true,
        )
        tracker.finalEvent()

        val events = tracker.events()
        assertTrue(
            events.any {
                it.kind == "data_plane_counter_reset" &&
                    it.message.contains("generation=2") &&
                    it.message.contains("layers=tun")
            },
        )
        val finalEvent = events.last()
        assertEquals("data_plane_final", finalEvent.kind)
        assertTrue(finalEvent.message.contains("state=evidence_unavailable_partial"))
        assertTrue(finalEvent.message.contains("generation=2"))
        assertTrue(finalEvent.message.contains("tun_write_bytes=0"))
        assertFalse(finalEvent.message.contains("state=cross_layer_return_observed"))
        assertFalse(finalEvent.message.contains("tun_write_bytes=100"))
    }

    @Test
    fun trackerEmitsOnlyOneFinalEvent() {
        val tracker = DataPlaneCorrelationTracker(Mode.Proxy, TestServiceClock(now = 10L))

        tracker.observe(
            proxyEvidence = ProxyForwardingEvidence(upstreamApplicationBytes = 64),
            tunEvidence = null,
            tunSupport = TunEvidenceSupport.Unsupported,
        )
        tracker.finalEvent()
        tracker.finalEvent()

        assertEquals(1, tracker.events().count { event -> event.kind == "data_plane_final" })
    }

    @Test
    fun unilateralResetRequiresOtherLayerToAdvanceBeforeCrossLayerClaim() {
        val tracker = DataPlaneCorrelationTracker(Mode.VPN, TestServiceClock())
        tracker.observe(
            ProxyForwardingEvidence(upstreamApplicationBytes = 900),
            TunForwardingEvidence(tunWriteBytes = 800),
            TunEvidenceSupport.Supported,
        )

        tracker.observe(
            ProxyForwardingEvidence(upstreamApplicationBytes = 9),
            TunForwardingEvidence(tunWriteBytes = 800),
            TunEvidenceSupport.Supported,
            proxyGenerationAuthoritative = true,
        )

        val afterProxyReset = tracker.events().last().message
        assertTrue(afterProxyReset.contains("state=evidence_unavailable_partial"))
        assertTrue(afterProxyReset.contains("proxy_outbound=observed"))
        assertTrue(afterProxyReset.contains("tun_return=unavailable"))
        assertFalse(afterProxyReset.contains("state=cross_layer_return_observed"))

        tracker.observe(
            ProxyForwardingEvidence(upstreamApplicationBytes = 9),
            TunForwardingEvidence(tunWriteBytes = 800, tunReadErrors = 1),
            TunEvidenceSupport.Supported,
        )

        val afterUnrelatedTunError = tracker.events().last().message
        assertTrue(afterUnrelatedTunError.contains("state=outbound_only"))
        assertTrue(afterUnrelatedTunError.contains("tun_return=not_observed"))
        assertTrue(afterUnrelatedTunError.contains("tun_write_bytes=0"))
        assertFalse(afterUnrelatedTunError.contains("state=cross_layer_return_observed"))

        tracker.observe(
            ProxyForwardingEvidence(upstreamApplicationBytes = 9),
            TunForwardingEvidence(tunWriteBytes = 801, tunReadErrors = 1),
            TunEvidenceSupport.Supported,
        )

        val afterTunAdvance = tracker.events().last().message
        assertTrue(afterTunAdvance.contains("state=cross_layer_return_observed"))
        assertTrue(afterTunAdvance.contains("generation=2"))
        assertTrue(afterTunAdvance.contains("tun_write_bytes=1"))
    }

    @Test
    fun unilateralTunResetUsesProxyFieldDeltasBeforeCrossLayerClaim() {
        val tracker = DataPlaneCorrelationTracker(Mode.VPN, TestServiceClock())
        tracker.observe(
            ProxyForwardingEvidence(upstreamApplicationBytes = 900),
            TunForwardingEvidence(tunWriteBytes = 800),
            TunEvidenceSupport.Supported,
        )

        tracker.observe(
            ProxyForwardingEvidence(upstreamApplicationBytes = 900),
            TunForwardingEvidence(tunWriteBytes = 8),
            TunEvidenceSupport.Supported,
            tunGenerationAuthoritative = true,
        )

        tracker.observe(
            ProxyForwardingEvidence(upstreamApplicationBytes = 900, upstreamOpenFailures = 1),
            TunForwardingEvidence(tunWriteBytes = 8),
            TunEvidenceSupport.Supported,
        )

        val afterUnrelatedProxyFailure = tracker.events().last().message
        assertTrue(afterUnrelatedProxyFailure.contains("state=tun_return_without_proxy_outbound"))
        assertTrue(afterUnrelatedProxyFailure.contains("proxy_outbound=not_observed"))
        assertTrue(afterUnrelatedProxyFailure.contains("proxy_application_bytes=0"))
        assertFalse(afterUnrelatedProxyFailure.contains("state=cross_layer_return_observed"))

        tracker.observe(
            ProxyForwardingEvidence(upstreamApplicationBytes = 901, upstreamOpenFailures = 1),
            TunForwardingEvidence(tunWriteBytes = 8),
            TunEvidenceSupport.Supported,
        )

        val afterProxyAdvance = tracker.events().last().message
        assertTrue(afterProxyAdvance.contains("state=cross_layer_return_observed"))
        assertTrue(afterProxyAdvance.contains("proxy_application_bytes=1"))
    }

    @Test
    fun timestampRollbackDoesNotStartCounterGeneration() {
        val tracker = DataPlaneCorrelationTracker(Mode.VPN, TestServiceClock())
        tracker.observe(
            ProxyForwardingEvidence(
                upstreamApplicationBytes = 10,
                lastUpstreamApplicationForwardedAt = 2_000,
            ),
            TunForwardingEvidence(
                tunWriteBytes = 10,
                lastTunWriteAtEpochMs = 2_000,
            ),
            TunEvidenceSupport.Supported,
        )
        tracker.observe(
            ProxyForwardingEvidence(
                upstreamApplicationBytes = 10,
                lastUpstreamApplicationForwardedAt = 1_000,
            ),
            TunForwardingEvidence(
                tunWriteBytes = 10,
                lastTunWriteAtEpochMs = 1_000,
            ),
            TunEvidenceSupport.Supported,
        )

        assertFalse(tracker.events().any { it.kind == "data_plane_counter_reset" })
    }

    @Test
    fun counterResetStartsNewGenerationWithoutMixingOldMaxima() {
        val tracker = DataPlaneCorrelationTracker(Mode.VPN, TestServiceClock())
        tracker.observe(
            proxyEvidence = ProxyForwardingEvidence(upstreamApplicationBytes = 900),
            tunEvidence = TunForwardingEvidence(tunWriteBytes = 800),
            tunSupport = TunEvidenceSupport.Supported,
        )
        tracker.observe(
            proxyEvidence = ProxyForwardingEvidence(upstreamApplicationBytes = 9),
            tunEvidence = TunForwardingEvidence(tunWriteBytes = 8),
            tunSupport = TunEvidenceSupport.Supported,
            proxyGenerationAuthoritative = true,
            tunGenerationAuthoritative = true,
        )

        val messages = tracker.events().map { it.message }
        assertTrue(messages.any { it.contains("state=counter_reset") && it.contains("generation=2") })
        val newGeneration = messages.last { it.contains("state=cross_layer_return_observed") }
        assertTrue(newGeneration.contains("generation=2"))
        assertTrue(newGeneration.contains("proxy_application_bytes=9"))
        assertTrue(newGeneration.contains("tun_write_bytes=8"))
        assertFalse(newGeneration.contains("proxy_application_bytes=900"))

        repeat(20) { index ->
            val high = 100L + index
            tracker.observe(
                ProxyForwardingEvidence(upstreamApplicationBytes = high),
                TunForwardingEvidence(tunWriteBytes = high),
                TunEvidenceSupport.Supported,
            )
            tracker.observe(
                ProxyForwardingEvidence(upstreamApplicationBytes = 1),
                TunForwardingEvidence(tunWriteBytes = 1),
                TunEvidenceSupport.Supported,
                proxyGenerationAuthoritative = true,
                tunGenerationAuthoritative = true,
            )
        }
        assertEquals(16, tracker.events().size)
        tracker.observe(
            ProxyForwardingEvidence(upstreamApplicationBytes = 2),
            TunForwardingEvidence(tunWriteBytes = 2),
            TunEvidenceSupport.Supported,
        )
        tracker.finalEvent()
        assertEquals(16, tracker.events().size)
        assertEquals("data_plane_final", tracker.events().last().kind)
        assertTrue(
            tracker
                .events()
                .last()
                .message
                .contains("final=true"),
        )
    }
}
