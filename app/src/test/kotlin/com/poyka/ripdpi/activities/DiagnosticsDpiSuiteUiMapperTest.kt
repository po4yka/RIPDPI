package com.poyka.ripdpi.activities

import android.app.Application
import com.poyka.ripdpi.data.diagnostics.DiagnosticsTlsClientState
import com.poyka.ripdpi.diagnostics.dpi.AttemptResult
import com.poyka.ripdpi.diagnostics.dpi.AttemptStatus
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityResult
import com.poyka.ripdpi.diagnostics.dpi.DomainVerdict
import com.poyka.ripdpi.diagnostics.dpi.DpiProbeKind
import com.poyka.ripdpi.diagnostics.dpi.DpiSuiteProbeResult
import com.poyka.ripdpi.diagnostics.dpi.EchProbeResult
import com.poyka.ripdpi.diagnostics.dpi.EchProbeVerdict
import com.poyka.ripdpi.diagnostics.dpi.QuicProbeResult
import com.poyka.ripdpi.diagnostics.dpi.QuicProbeVerdict
import com.poyka.ripdpi.diagnostics.dpi.Tcp16ProbeResult
import com.poyka.ripdpi.diagnostics.dpi.Tcp16Verdict
import com.poyka.ripdpi.platform.StringResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsDpiSuiteUiMapperTest {
    private val stringResolver = ResourceStringResolver()

    @Test
    fun domainReachabilityRowIncludesDiagnosticTlsFallbackState() {
        val row =
            DpiSuiteProbeResult
                .DomainReachability(
                    listOf(
                        DomainReachabilityResult(
                            domain = "example.com",
                            resolvedIps = listOf("93.184.216.34"),
                            tls13 = AttemptResult(AttemptStatus.OK),
                            tls12 = AttemptResult(AttemptStatus.OK),
                            http = AttemptResult(AttemptStatus.OK),
                            verdict = DomainVerdict.OK,
                            tlsClientState = fallbackTlsState(),
                        ),
                    ),
                ).toDpiSuiteProbeRowUiModel(stringResolver)

        assertTrue(row.detailRows.any { detail -> detail.label == "TLS profile" && detail.detail == "chrome_stable" })
        assertEquals(
            DiagnosticsTone.Warning,
            row.detailRows.first { detail -> detail.label == "TLS mode" }.tone,
        )
    }

    @Test
    fun tcp16RowIncludesDiagnosticTlsFallbackState() {
        val row =
            DpiSuiteProbeResult
                .Tcp16(
                    listOf(
                        Tcp16ProbeResult(
                            targetId = "target",
                            asn = "AS64500",
                            provider = "Example",
                            ip = "192.0.2.1",
                            port = 443,
                            alive = true,
                            verdict = Tcp16Verdict.OK,
                            detectedAtKb = null,
                            measuredRttMs = 20,
                            errorDetail = null,
                            connectionCount = 1,
                            tlsClientState = fallbackTlsState(),
                        ),
                    ),
                ).toDpiSuiteProbeRowUiModel(stringResolver)

        assertTrue(
            row.detailRows.any { detail ->
                detail.label == "TLS mode" && detail.detail == "Android template fallback"
            },
        )
    }

    @Test
    fun quicH3RowIncludesPerTargetFingerprintMatrix() {
        val row =
            DpiSuiteProbeResult
                .QuicH3(
                    listOf(
                        QuicProbeResult(
                            target = "cloudflare.com",
                            verdict = QuicProbeVerdict.QUIC_DPI_FINGERPRINT_BLOCK,
                            chromeOk = false,
                            firefoxOk = true,
                            genericOk = true,
                            vnOk = true,
                            udpReachable = true,
                            serverInitialLatencyMs = 42,
                        ),
                    ),
                ).toDpiSuiteProbeRowUiModel(stringResolver)

        assertEquals(DpiProbeKind.QUIC_H3, row.kind)
        assertEquals("QUIC/H3 fingerprint", row.label)
        assertEquals("flagged", row.status)
        assertEquals(1, row.detailRows.size)
        assertEquals("cloudflare.com", row.detailRows.single().label)
        assertEquals("Chrome blocked | Firefox ok | Generic ok | VN ok | 42 ms", row.detailRows.single().detail)
        assertEquals(DiagnosticsTone.Warning, row.detailRows.single().tone)
    }

    @Test
    fun echReadinessRowIncludesPerTargetHandshakeState() {
        val row =
            DpiSuiteProbeResult
                .EchReadiness(
                    listOf(
                        EchProbeResult(
                            target = "cloudflare.com",
                            verdict = EchProbeVerdict.ECH_OK,
                            httpsRrFetched = true,
                            echConfigBytesB64 = "AQID",
                            tlsLatencyMs = 42,
                            negotiatedEch = true,
                            errorDetail = null,
                            vanillaTlsOk = false,
                        ),
                    ),
                ).toDpiSuiteProbeRowUiModel(stringResolver)

        assertEquals(DpiProbeKind.ECH_READINESS, row.kind)
        assertEquals("ECH readiness", row.label)
        assertEquals("ok", row.status)
        assertEquals("1/1 targets accepted ECH", row.detail)
        assertEquals(1, row.detailRows.size)
        assertEquals("cloudflare.com", row.detailRows.single().label)
        assertEquals("ECH ok | HTTPS RR yes | negotiated yes | vanilla blocked | 42 ms", row.detailRows.single().detail)
        assertEquals(DiagnosticsTone.Positive, row.detailRows.single().tone)
    }

    private fun fallbackTlsState(): DiagnosticsTlsClientState =
        DiagnosticsTlsClientState(
            profileId = "chrome_stable",
            nativeOwnedTlsAvailable = false,
            fallbackActive = true,
            fallbackReason = "android_okhttp_fingerprint_template",
        )

    private class ResourceStringResolver : StringResolver {
        private val application: Application = RuntimeEnvironment.getApplication()

        override fun getString(
            resId: Int,
            vararg formatArgs: Any,
        ): String = application.getString(resId, *formatArgs)
    }
}
