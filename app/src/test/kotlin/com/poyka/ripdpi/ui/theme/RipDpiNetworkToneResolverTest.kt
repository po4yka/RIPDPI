package com.poyka.ripdpi.ui.theme

import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.data.TransportKind
import com.poyka.ripdpi.ui.components.feedback.RipDpiDegradationTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RipDpiNetworkToneResolverTest {
    private val thresholds = DefaultRipDpiNetworkQualityThresholds

    private fun snap(
        loss: Float,
        rtt: Long,
        jitter: Long,
        count: Long = 100,
    ): ConnectionQualitySnapshot =
        ConnectionQualitySnapshot(
            lossPct = loss,
            rttP50Ms = rtt,
            rttP95Ms = rtt,
            jitterMs = jitter,
            sampleCount = count,
            windowStartAtMs = 0,
            transportKind = TransportKind.TcpTunnel,
        )

    @Test
    fun belowSampleCount_returnsNull() {
        // Even egregious values are suppressed until we have minSampleCountForVerdict (=12) samples.
        assertNull(resolveDegradationTone(snap(50f, 1000, 200, count = 5)))
    }

    @Test
    fun allBelowWarn_returnsNull() {
        assertNull(resolveDegradationTone(snap(0.5f, 100, 5)))
    }

    @Test
    fun lossOverWarn_returnsWarning() {
        assertEquals(
            RipDpiDegradationTone.Warning,
            resolveDegradationTone(snap(3f, 100, 5)),
        )
    }

    @Test
    fun rttOverCritical_returnsCritical() {
        assertEquals(
            RipDpiDegradationTone.Critical,
            resolveDegradationTone(snap(0f, 900, 5)),
        )
    }

    @Test
    fun jitterOverWarn_returnsWarning() {
        assertEquals(
            RipDpiDegradationTone.Warning,
            resolveDegradationTone(snap(0f, 100, 50)),
        )
    }

    @Test
    fun lossOverCritical_returnsCritical() {
        assertEquals(
            RipDpiDegradationTone.Critical,
            resolveDegradationTone(snap(10f, 100, 5)),
        )
    }

    @Test
    fun jitterOverCritical_returnsCritical() {
        assertEquals(
            RipDpiDegradationTone.Critical,
            resolveDegradationTone(snap(0f, 100, 120)),
        )
    }

    @Test
    fun sampleCountAtThreshold_isEligible() {
        // Boundary: sampleCount == minSampleCountForVerdict must NOT suppress.
        assertEquals(
            RipDpiDegradationTone.Warning,
            resolveDegradationTone(
                snap(3f, 100, 5, count = thresholds.minSampleCountForVerdict),
            ),
        )
    }

    @Test
    fun defaultThresholds_matchDesignDecision() {
        assertEquals(2.0f, thresholds.lossWarnPct)
        assertEquals(8.0f, thresholds.lossCriticalPct)
        assertEquals(300L, thresholds.rttWarnMs)
        assertEquals(800L, thresholds.rttCriticalMs)
        assertEquals(30L, thresholds.jitterWarnMs)
        assertEquals(100L, thresholds.jitterCriticalMs)
        assertEquals(12L, thresholds.minSampleCountForVerdict)
        assertEquals(0.7f, thresholds.hysteresisFactor)
    }
}
