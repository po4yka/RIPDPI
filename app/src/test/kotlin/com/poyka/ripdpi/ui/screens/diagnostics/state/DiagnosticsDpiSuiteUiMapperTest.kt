package com.poyka.ripdpi.ui.screens.diagnostics.state

import android.app.Application
import com.poyka.ripdpi.activities.ResourceStringResolver
import com.poyka.ripdpi.data.diagnostics.DiagnosticsTlsClientState
import com.poyka.ripdpi.diagnostics.dpi.AttemptResult
import com.poyka.ripdpi.diagnostics.dpi.AttemptStatus
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityResult
import com.poyka.ripdpi.diagnostics.dpi.DomainVerdict
import com.poyka.ripdpi.diagnostics.dpi.DpiProbeKind
import com.poyka.ripdpi.diagnostics.dpi.DpiSuiteProbeResult
import com.poyka.ripdpi.diagnostics.dpi.EchProbeResult
import com.poyka.ripdpi.diagnostics.dpi.EchProbeVerdict
import com.poyka.ripdpi.diagnostics.dpi.HttpsRrLookupAttempt
import com.poyka.ripdpi.diagnostics.dpi.HttpsRrOutcome
import com.poyka.ripdpi.diagnostics.dpi.HttpsRrSource
import com.poyka.ripdpi.diagnostics.dpi.QuicFingerprint
import com.poyka.ripdpi.diagnostics.dpi.QuicProbeOutcome
import com.poyka.ripdpi.diagnostics.dpi.QuicProbeResult
import com.poyka.ripdpi.diagnostics.dpi.QuicProbeSample
import com.poyka.ripdpi.diagnostics.dpi.QuicProbeStartFailure
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
    fun quicRowShowsReplyKindsAndTheirDelays() {
        val row =
            DpiSuiteProbeResult
                .QuicH3(
                    listOf(
                        QuicProbeResult(
                            target = "example.test",
                            peerAddress = "192.0.2.1",
                            attempts =
                                mapOf(
                                    QuicFingerprint.CHROME_120 to listOf(QuicProbeSample(QuicProbeOutcome.NO_RESPONSE)),
                                    QuicFingerprint.FIREFOX_121 to
                                        listOf(QuicProbeSample(QuicProbeOutcome.INITIAL_CRYPTO, latencyMs = 42)),
                                    QuicFingerprint.GENERIC_V1 to listOf(QuicProbeSample(QuicProbeOutcome.RETRY)),
                                    QuicFingerprint.VN_PROBE to
                                        listOf(QuicProbeSample(QuicProbeOutcome.VERSION_NEGOTIATION)),
                                ),
                        ),
                    ),
                    skippedTargetCount = 2,
                ).toDpiSuiteProbeRowUiModel(stringResolver)

        assertEquals(DpiProbeKind.QUIC_H3, row.kind)
        assertEquals("QUIC Initial/VN", row.label)
        assertEquals("partial", row.status)
        assertEquals("Targets with complete Initial/VN replies: 0/3 · Not measured: 2", row.detail)
        val target = row.detailRows.single()
        assertEquals("example.test · 192.0.2.1", target.label)
        assertEquals(
            "Template A Timed out | Template B Initial + CRYPTO (42 ms) | Generic Retry | VN Observed",
            target.detail,
        )
        assertEquals(DiagnosticsTone.Warning, target.tone)
    }

    @Test
    fun quicRowWithoutReplyEvidenceIsNeutral() {
        val noTargets = DpiSuiteProbeResult.QuicH3(emptyList()).toDpiSuiteProbeRowUiModel(stringResolver)
        assertEquals("inconclusive", noTargets.status)
        assertEquals(DiagnosticsTone.Neutral, noTargets.tone)

        val dnsFailure =
            DpiSuiteProbeResult
                .QuicH3(listOf(QuicProbeResult("invalid.test", startFailure = QuicProbeStartFailure.DNS_ERROR)))
                .toDpiSuiteProbeRowUiModel(stringResolver)
        assertEquals("inconclusive", dnsFailure.status)
        assertEquals("DNS resolution failed", dnsFailure.detailRows.single().detail)
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
        assertEquals("Targets that accepted ECH: 1/1", row.detail)
        assertEquals(1, row.detailRows.size)
        assertEquals("cloudflare.com", row.detailRows.single().label)
        assertEquals("ECH ok | HTTPS RR yes | negotiated yes | vanilla blocked | 42 ms", row.detailRows.single().detail)
        assertEquals(DiagnosticsTone.Positive, row.detailRows.single().tone)
    }

    @Test
    fun allUnmeasuredEchTargetsAreInconclusiveAndShowLookupEvidence() {
        val row =
            DpiSuiteProbeResult
                .EchReadiness(
                    listOf(
                        EchProbeResult(
                            target = "example.com",
                            verdict = EchProbeVerdict.ECH_LOOKUP_INCONCLUSIVE,
                            httpsRrFetched = false,
                            echConfigBytesB64 = null,
                            tlsLatencyMs = null,
                            negotiatedEch = false,
                            errorDetail = null,
                            httpsLookups =
                                listOf(
                                    HttpsRrLookupAttempt(HttpsRrSource.DOH, HttpsRrOutcome.HTTP_ERROR, null, 503),
                                    HttpsRrLookupAttempt(
                                        HttpsRrSource.SYSTEM,
                                        HttpsRrOutcome.TRANSPORT_ERROR,
                                        null,
                                        null,
                                    ),
                                ),
                        ),
                    ),
                ).toDpiSuiteProbeRowUiModel(stringResolver)

        assertEquals(DiagnosticsTone.Neutral, row.tone)
        assertEquals("Inconclusive", row.status)
        assertTrue(
            row.detailRows
                .single()
                .detail
                .contains("Transport error (HTTP 503)"),
        )
        assertTrue(
            row.detailRows
                .single()
                .detail
                .contains("System DNS: Transport error"),
        )
    }

    private fun fallbackTlsState(): DiagnosticsTlsClientState =
        DiagnosticsTlsClientState(
            profileId = "chrome_stable",
            nativeOwnedTlsAvailable = false,
            fallbackActive = true,
            fallbackReason = "android_okhttp_fingerprint_template",
        )

    private class ResourceStringResolver : StringResolver {
        override fun getQuantityString(
            resId: Int,
            quantity: Int,
            vararg formatArgs: Any,
        ): String =
            org.robolectric.RuntimeEnvironment
                .getApplication()
                .resources
                .getQuantityString(resId, quantity, *formatArgs)

        private val application: Application = RuntimeEnvironment.getApplication()

        override fun getString(
            resId: Int,
            vararg formatArgs: Any,
        ): String = application.getString(resId, *formatArgs)
    }
}
