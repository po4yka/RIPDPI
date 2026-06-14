package com.poyka.ripdpi.activities

import android.app.Application
import com.poyka.ripdpi.data.diagnostics.DiagnosticsTlsClientState
import com.poyka.ripdpi.diagnostics.dpi.AttemptResult
import com.poyka.ripdpi.diagnostics.dpi.AttemptStatus
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityResult
import com.poyka.ripdpi.diagnostics.dpi.DomainVerdict
import com.poyka.ripdpi.diagnostics.dpi.Tcp16ProbeResult
import com.poyka.ripdpi.diagnostics.dpi.Tcp16Verdict
import com.poyka.ripdpi.diagnostics.rkn.RknCheckResult
import com.poyka.ripdpi.diagnostics.rkn.RknConfidence
import com.poyka.ripdpi.diagnostics.rkn.RknVerdict
import com.poyka.ripdpi.platform.StringResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsDpiToolsTlsStateUiMapperTest {
    private val stringResolver = ResourceStringResolver()

    @Test
    fun `domain reachability metrics include diagnostic tls fallback state`() {
        val model =
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
            ).toUiModel(stubIpCount = 0, stringResolver = stringResolver)

        assertTrue(model.metrics.any { metric -> metric.label == "TLS profile" && metric.value == "chrome_stable" })
        assertEquals(
            DiagnosticsTone.Warning,
            model.metrics.first { metric -> metric.label == "TLS mode" }.tone,
        )
    }

    @Test
    fun `tcp16 metrics include diagnostic tls fallback state`() {
        val model =
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
            ).toTcp16UiModel(stringResolver)

        assertTrue(
            model.metrics.any { metric ->
                metric.label == "TLS mode" && metric.value == "Android template fallback"
            },
        )
    }

    @Test
    fun `rkn diagnosis metrics include diagnostic tls fallback state`() {
        val model =
            buildRknBlockDiagnosisUiModel(
                RknDiagnosisRunResult(
                    controlResults = listOf(rknResult("control", fallbackTlsState())),
                    testResults = listOf(rknResult("test", fallbackTlsState())),
                    fetchSelfInfoEnabled = false,
                    selfInfoPrivacyOverridden = false,
                    selfInfo = null,
                ),
                stringResolver,
            )

        assertTrue(model.metrics.any { metric -> metric.label == "TLS profile" && metric.value == "chrome_stable" })
        assertTrue(
            model.metrics.any { metric ->
                metric.label == "TLS mode" && metric.value == "Android template fallback"
            },
        )
    }

    private fun fallbackTlsState(): DiagnosticsTlsClientState =
        DiagnosticsTlsClientState(
            profileId = "chrome_stable",
            nativeOwnedTlsAvailable = false,
            fallbackActive = true,
            fallbackReason = "android_okhttp_fingerprint_template",
        )

    private fun rknResult(
        name: String,
        tlsState: DiagnosticsTlsClientState,
    ): RknCheckResult =
        RknCheckResult(
            name = name,
            url = "https://$name.example/",
            verdict = RknVerdict.OK,
            confidence = RknConfidence.HIGH,
            tlsClientState = tlsState,
        )

    private class ResourceStringResolver : StringResolver {
        private val application: Application = RuntimeEnvironment.getApplication()

        override fun getString(
            resId: Int,
            vararg formatArgs: Any,
        ): String = application.getString(resId, *formatArgs)
    }
}
