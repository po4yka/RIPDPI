package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectModeVerdictResult
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DirectModeVerdict
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class DiagnosticsUiCoreSupportTest {
    @Test
    fun `format timestamp uses injected zone`() {
        val support =
            DiagnosticsUiCoreSupport(
                object : DiagnosticsUiFormatter() {
                    override val locale: Locale = Locale.US
                    override val zoneId: ZoneId = ZoneId.of("America/New_York")
                },
            )

        assertEquals("Dec 31, 19:00", support.formatTimestamp(0L))
    }

    @Test
    fun `formatters use injected locale`() {
        val support =
            DiagnosticsUiCoreSupport(
                object : DiagnosticsUiFormatter() {
                    override val locale: Locale = Locale.FRANCE
                    override val zoneId: ZoneId = ZoneId.of("UTC")
                },
            )

        assertEquals("1,5 KB", support.formatBytes(1_500L))
        assertEquals("1,5 Kbps", support.formatBps(1_500L))
        assertEquals("2h 05m", support.formatDurationMs(7_500_000L))
    }

    @Test
    fun `probe tone uses canonical taxonomy`() {
        val support = DiagnosticsUiCoreSupport()

        assertEquals(
            DiagnosticsTone.Positive,
            support.toneForProbeOutcome("dns_integrity", ScanPathMode.RAW_PATH, "dns_match"),
        )
        assertEquals(
            DiagnosticsTone.Warning,
            support.toneForProbeOutcome("dns_integrity", ScanPathMode.RAW_PATH, "udp_blocked"),
        )
        assertEquals(
            DiagnosticsTone.Negative,
            support.toneForProbeOutcome("network_environment", ScanPathMode.RAW_PATH, "network_unavailable"),
        )
    }

    @Test
    fun `session row tone comes from report results before status`() {
        val support = DiagnosticsUiCoreSupport()
        val session =
            DiagnosticScanSession(
                id = "session-1",
                profileId = "profile-1",
                pathMode = ScanPathMode.RAW_PATH.name,
                serviceMode = "VPN",
                status = "completed",
                summary = "Completed",
                report =
                    DiagnosticsSessionProjection(
                        results =
                            listOf(
                                ProbeResult(
                                    probeType = "tcp_fat_header",
                                    target = "1.1.1.1:443 (Cloudflare)",
                                    outcome = "whitelist_sni_failed",
                                ),
                            ),
                    ),
                startedAt = 0L,
                finishedAt = 1L,
            )

        assertEquals(DiagnosticsTone.Negative, support.toSessionRowUiModel(session).tone)
    }

    @Test
    fun `event level tone uses explicit mapping`() {
        val support = DiagnosticsUiCoreSupport()

        assertEquals(DiagnosticsTone.Info, support.toneForEventLevel("info"))
        assertEquals(DiagnosticsTone.Warning, support.toneForEventLevel("warn"))
        assertEquals(DiagnosticsTone.Negative, support.toneForEventLevel("error"))
    }

    @Test
    fun `session row exposes owned stack launch target when verdict requires owned stack`() {
        val support = DiagnosticsUiCoreSupport()
        val session =
            DiagnosticScanSession(
                id = "session-owned-stack",
                profileId = "profile-1",
                pathMode = ScanPathMode.RAW_PATH.name,
                serviceMode = "VPN",
                status = "completed",
                summary = "Owned stack required",
                report =
                    DiagnosticsSessionProjection(
                        directModeVerdict =
                            DirectModeVerdict(
                                result = DirectModeVerdictResult.OWNED_STACK_ONLY,
                                authority = "example.org:443",
                            ),
                    ),
                startedAt = 0L,
                finishedAt = 1L,
            )

        val row = support.toSessionRowUiModel(session)

        assertEquals("https://example.org:443/", row.ownedStackLaunchUrl)
        assertEquals(true, row.ownedStackOnly)
        assertEquals(DirectModeVerdictResult.OWNED_STACK_ONLY, row.directModeResult)
        assertEquals(null, row.directModeReasonCode)
        assertEquals(null, row.directTransportClass)
    }

    @Test
    fun `session row preserves transport verdict metadata for remediation branching`() {
        val support = DiagnosticsUiCoreSupport()
        val session =
            DiagnosticScanSession(
                id = "session-transport",
                profileId = "profile-1",
                pathMode = ScanPathMode.RAW_PATH.name,
                serviceMode = "VPN",
                status = "completed",
                summary = "Direct mode unavailable",
                report =
                    DiagnosticsSessionProjection(
                        directModeVerdict =
                            DirectModeVerdict(
                                result = DirectModeVerdictResult.NO_DIRECT_SOLUTION,
                                reasonCode = DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE,
                                transportClass = DirectTransportClass.SNI_TLS_SUSPECT,
                            ),
                    ),
                startedAt = 0L,
                finishedAt = 1L,
            )

        val row = support.toSessionRowUiModel(session)

        assertEquals(DirectModeVerdictResult.NO_DIRECT_SOLUTION, row.directModeResult)
        assertEquals(DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE, row.directModeReasonCode)
        assertEquals(DirectTransportClass.SNI_TLS_SUSPECT, row.directTransportClass)
    }
}
