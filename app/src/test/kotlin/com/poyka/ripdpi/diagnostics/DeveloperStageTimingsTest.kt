package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DeveloperStageTimingsTest {
    @Test
    fun `collector maps exported stage timing evidence without dropping cpu or phases`() {
        val context =
            DeveloperAnalyticsContext(
                archiveCreatedAtMs = 1,
                archiveFileName = "ripdpi-analysis.zip",
                stageTimings =
                    listOf(
                        DeveloperStageTimingEvidence(
                            stageKey = "default_connectivity",
                            wallClockMs = 900,
                            cpuMs = 240,
                            dnsMs = 40,
                            tcpHandshakeMs = 31,
                            tlsHandshakeMs = 47,
                            ttfbMs = 19,
                            notes = listOf("cpu_scope=app_process_during_stage_window"),
                        ),
                    ),
            )

        val timing = buildDeveloperStageTimings(context).single()

        assertEquals(900L, timing.wallClockMs)
        assertEquals(240L, timing.cpuMs)
        assertEquals(40L, timing.dnsMs)
        assertEquals(31L, timing.tcpHandshakeMs)
        assertEquals(47L, timing.tlsHandshakeMs)
        assertEquals(19L, timing.ttfbMs)
    }
}
