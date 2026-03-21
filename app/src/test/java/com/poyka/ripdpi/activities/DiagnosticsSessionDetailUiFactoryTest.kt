package com.poyka.ripdpi.activities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsSessionDetailUiFactoryTest {
    private val support = DiagnosticsUiFactorySupport(RuntimeEnvironment.getApplication())
    private val factory = DiagnosticsSessionDetailUiFactory(support)

    @Test
    fun `session detail factory groups probes and preserves visibility flags`() {
        val detail =
            factory.toSessionDetailUiModel(
                detail =
                    historyDiagnosticsDetail("scan-1").copy(
                        results =
                            listOf(
                                historyProbeResult(id = "probe-1", sessionId = "scan-1"),
                                historyProbeResult(id = "probe-2", sessionId = "scan-1").copy(probeType = "http"),
                            ),
                    ),
                showSensitiveDetails = true,
            )

        assertEquals("scan-1", detail.session.id)
        assertEquals(2, detail.probeGroups.size)
        assertEquals(1, detail.snapshots.size)
        assertEquals(1, detail.events.size)
        assertTrue(detail.contextGroups.isNotEmpty())
        assertTrue(detail.hasSensitiveDetails)
        assertTrue(detail.sensitiveDetailsVisible)
    }
}
