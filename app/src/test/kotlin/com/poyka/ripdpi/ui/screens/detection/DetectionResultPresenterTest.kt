package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.core.detection.BypassPortRange
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionResultPresenterTest {
    private fun settings(block: AppSettings.Builder.() -> Unit = {}): AppSettings =
        AppSettings
            .getDefaultInstance()
            .toBuilder()
            .apply(block)
            .build()

    @Test
    fun `runnerConfig sets ownProxyPort only when proxyPort positive`() {
        assertNull(DetectionResultPresenter.runnerConfig(settings { proxyPort = 0 }, "pkg").ownProxyPort)
        assertEquals(
            8080,
            DetectionResultPresenter.runnerConfig(settings { proxyPort = 8080 }, "pkg").ownProxyPort,
        )
    }

    @Test
    fun `runnerConfig network feature flags require network requests and per-feature flag`() {
        val withoutNetwork =
            settings {
                detectionCheckNetworkRequestsEnabled = false
                detectionCheckRttTriangulationEnabled = true
                detectionCheckCdnPullingEnabled = true
                detectionCheckCallTransportProbeEnabled = true
            }
        DetectionResultPresenter.runnerConfig(withoutNetwork, "pkg").let {
            assertTrue(!it.includeRttTriangulationCheck)
            assertTrue(!it.includeCdnPullingCheck)
            assertTrue(!it.includeCallTransportCheck)
        }

        val withNetwork =
            settings {
                detectionCheckNetworkRequestsEnabled = true
                detectionCheckRttTriangulationEnabled = true
                detectionCheckCdnPullingEnabled = true
                detectionCheckCallTransportProbeEnabled = true
            }
        DetectionResultPresenter.runnerConfig(withNetwork, "pkg").let {
            assertTrue(it.includeRttTriangulationCheck)
            assertTrue(it.includeCdnPullingCheck)
            assertTrue(it.includeCallTransportCheck)
        }

        val networkOnly =
            settings {
                detectionCheckNetworkRequestsEnabled = true
                detectionCheckRttTriangulationEnabled = false
                detectionCheckCdnPullingEnabled = false
                detectionCheckCallTransportProbeEnabled = false
            }
        DetectionResultPresenter.runnerConfig(networkOnly, "pkg").let {
            assertTrue(!it.includeRttTriangulationCheck)
            assertTrue(!it.includeCdnPullingCheck)
            assertTrue(!it.includeCallTransportCheck)
        }
    }

    @Test
    fun `runnerConfig encryptedDnsEnabled when dnsMode encrypted or resolver doh`() {
        assertTrue(DetectionResultPresenter.runnerConfig(settings { dnsMode = "encrypted" }, "pkg").encryptedDnsEnabled)
        assertTrue(
            DetectionResultPresenter
                .runnerConfig(settings { detectionCheckDnsResolverMode = "doh" }, "pkg")
                .encryptedDnsEnabled,
        )
        assertTrue(!DetectionResultPresenter.runnerConfig(settings { dnsMode = "plain" }, "pkg").encryptedDnsEnabled)
    }

    @Test
    fun `runnerConfig tlsFingerprintProfile defaults to chrome_stable when empty`() {
        assertEquals(
            "chrome_stable",
            DetectionResultPresenter.runnerConfig(settings { tlsFingerprintProfile = "" }, "pkg").tlsFingerprintProfile,
        )
        assertEquals(
            "firefox",
            DetectionResultPresenter
                .runnerConfig(settings { tlsFingerprintProfile = "firefox" }, "pkg")
                .tlsFingerprintProfile,
        )
    }

    @Test
    fun `runnerConfig bypass port range maps each mode`() {
        fun range(mode: String): BypassPortRange =
            DetectionResultPresenter
                .runnerConfig(settings { detectionCheckPortRangeMode = mode }, "pkg")
                .bypassScanOptions
                .portRange

        assertEquals(BypassPortRange.Popular, range("popular"))
        assertEquals(BypassPortRange.Extended, range("extended"))
        assertEquals(BypassPortRange.Full, range("full"))
    }

    @Test
    fun `runnerConfig custom port range uses configured then fallback values`() {
        val configured =
            DetectionResultPresenter
                .runnerConfig(
                    settings {
                        detectionCheckPortRangeMode = "custom"
                        detectionCheckCustomPortStart = 2000
                        detectionCheckCustomPortEnd = 3000
                    },
                    "pkg",
                ).bypassScanOptions
                .portRange
        assertEquals(BypassPortRange.Custom(start = 2000, end = 3000), configured)

        val fallback =
            DetectionResultPresenter
                .runnerConfig(settings { detectionCheckPortRangeMode = "custom" }, "pkg")
                .bypassScanOptions
                .portRange
        assertEquals(BypassPortRange.Custom(start = 1080, end = 1090), fallback)
    }

    @Test
    fun `debugReportText is null when debug mode off`() {
        val result = detectionResult()
        assertNull(
            DetectionResultPresenter.debugReportText(
                result = result,
                settings = settings { detectionCheckDebugModeEnabled = false },
                privacyModeEnabled = false,
            ),
        )
        assertNotNull(
            DetectionResultPresenter.debugReportText(
                result = result,
                settings = settings { detectionCheckDebugModeEnabled = true },
                privacyModeEnabled = false,
            ),
        )
    }

    @Test
    fun `recommendations preserve generate-then-routing order`() {
        val result = detectionResult(Verdict.NOT_DETECTED)
        val recommendations =
            DetectionResultPresenter.recommendations(
                result = result,
                settings = settings(),
                snapshot = RoutingProtectionCatalogSnapshot(),
                stringResolver = RecordingStringResolver(),
            )
        // Empty snapshot contributes no routing recommendations; the list equals the generated set.
        val generated =
            com.poyka.ripdpi.core.detection.DetectionRecommendations
                .generate(result)
        assertEquals(generated, recommendations)
    }

    @Test
    fun `suggestedFixes delegates to AppSettings extension`() {
        val result = detectionResult()
        val expected = settings().suggestDetectionFixes(result)
        assertEquals(expected, DetectionResultPresenter.suggestedFixes(settings(), result))
    }
}
