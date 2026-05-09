package com.poyka.ripdpi.core.detection

import android.content.Context
import com.poyka.ripdpi.core.detection.checker.BypassChecker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DetectionCheckRunnerTest {
    @Test
    fun `runner schedules enabled checks reports progress and assembles verdict`() =
        runTest {
            val ports = FakeDetectionPorts()
            val runner = ports.newRunner()
            val progress = mutableListOf<DetectionProgress>()

            val result =
                runner.run(
                    context = RuntimeEnvironment.getApplication(),
                    config =
                        DetectionRunnerConfig(
                            ownProxyPort = 1080,
                            ownPackageName = "com.example.proxy",
                            encryptedDnsEnabled = true,
                            webRtcProtectionEnabled = true,
                            tlsFingerprintProfile = "firefox",
                        ),
                    onProgress = { progress += it },
                )

            assertEquals(Verdict.NEEDS_REVIEW, result.verdict)
            assertEquals("geo", result.geoIp.name)
            assertEquals("direct", result.directSigns.name)
            assertEquals("indirect", result.indirectSigns.name)
            assertEquals("location", result.locationSignals.name)
            assertEquals("dns", result.dnsLeak?.name)
            assertEquals("webrtc", result.webRtcLeak?.name)
            assertEquals("tls", result.tlsFingerprint?.name)
            assertEquals("timing", result.timingAnalysis?.name)
            assertEquals(setOf(1080), ports.bypass.excludePorts)
            assertEquals("com.example.proxy", ports.direct.excludePackage)
            assertEquals(true, ports.dns.encryptedDnsEnabled)
            assertEquals(true, ports.webRtc.webRtcProtectionEnabled)
            assertEquals("firefox", ports.tls.tlsFingerprintProfile)
            assertSame(ports.geo.result, ports.verdict.geoIp)
            assertSame(ports.bypass.result, ports.verdict.bypassResult)
            assertTrue(progress.any { it.stage == DetectionStage.GEO_IP && it.detail == "Done" })
            assertTrue(progress.any { it.stage == DetectionStage.BYPASS && it.label == "Bypass: scan" })
        }

    @Test
    fun `runner uses disabled defaults without calling disabled ports`() =
        runTest {
            val ports = FakeDetectionPorts()
            val runner = ports.newRunner()

            val result =
                runner.run(
                    context = RuntimeEnvironment.getApplication(),
                    config =
                        DetectionRunnerConfig(
                            includeBypassCheck = false,
                            includeLocationCheck = false,
                            includeDnsLeakCheck = false,
                            includeWebRtcCheck = false,
                            includeTlsFingerprintCheck = false,
                            includeTimingAnalysis = false,
                        ),
                )

            assertEquals("Location signals", result.locationSignals.name)
            assertEquals(
                "Location check disabled",
                result.locationSignals.findings
                    .single()
                    .description,
            )
            assertEquals(
                "Bypass check disabled",
                result.bypassResult.findings
                    .single()
                    .description,
            )
            assertNull(result.dnsLeak)
            assertNull(result.webRtcLeak)
            assertNull(result.tlsFingerprint)
            assertNull(result.timingAnalysis)
            assertEquals(0, ports.location.calls)
            assertEquals(0, ports.bypass.calls)
            assertEquals(0, ports.dns.calls)
            assertEquals(0, ports.webRtc.calls)
            assertEquals(0, ports.tls.calls)
            assertEquals(0, ports.timing.calls)
            assertNotNull(ports.verdict.locationSignals)
            assertNotNull(ports.verdict.bypassResult)
        }

    private class FakeDetectionPorts {
        val geo = FakeGeoIpCheckerPort()
        val direct = FakeDirectSignsCheckerPort()
        val indirect = FakeIndirectSignsCheckerPort()
        val location = FakeLocationSignalsCheckerPort()
        val bypass = FakeBypassCheckerPort()
        val dns = FakeDnsLeakCheckerPort()
        val webRtc = FakeWebRtcLeakCheckerPort()
        val tls = FakeTlsFingerprintCheckerPort()
        val timing = FakeTimingAnalysisCheckerPort()
        val verdict = FakeDetectionVerdictEvaluator()

        fun newRunner(): DefaultDetectionCheckRunner =
            DefaultDetectionCheckRunner(
                geoIpChecker = geo,
                directSignsChecker = direct,
                indirectSignsChecker = indirect,
                locationSignalsChecker = location,
                bypassChecker = bypass,
                dnsLeakChecker = dns,
                webRtcLeakChecker = webRtc,
                tlsFingerprintChecker = tls,
                timingAnalysisChecker = timing,
                verdictEvaluator = verdict,
            )
    }

    private class FakeGeoIpCheckerPort : GeoIpCheckerPort {
        val result = category("geo")

        override suspend fun check(): CategoryResult = result
    }

    private class FakeDirectSignsCheckerPort : DirectSignsCheckerPort {
        val result = category("direct")
        var excludePackage: String? = null

        override fun check(
            context: Context,
            excludePackage: String?,
        ): CategoryResult {
            this.excludePackage = excludePackage
            return result
        }
    }

    private class FakeIndirectSignsCheckerPort : IndirectSignsCheckerPort {
        val result = category("indirect")

        override fun check(context: Context): CategoryResult = result
    }

    private class FakeLocationSignalsCheckerPort : LocationSignalsCheckerPort {
        val result = category("location")
        var calls = 0

        override fun check(context: Context): CategoryResult {
            calls += 1
            return result
        }
    }

    private class FakeBypassCheckerPort : BypassCheckerPort {
        val result = bypass()
        var calls = 0
        var excludePorts: Set<Int> = emptySet()

        override suspend fun check(
            excludePorts: Set<Int>,
            onProgress: (suspend (BypassChecker.Progress) -> Unit)?,
        ): BypassResult {
            calls += 1
            this.excludePorts = excludePorts
            onProgress?.invoke(BypassChecker.Progress(phase = "scan", detail = "checking ports"))
            return result
        }
    }

    private class FakeDnsLeakCheckerPort : DnsLeakCheckerPort {
        val result = category("dns")
        var calls = 0
        var encryptedDnsEnabled: Boolean? = null

        override suspend fun check(
            context: Context,
            encryptedDnsEnabled: Boolean,
        ): CategoryResult {
            calls += 1
            this.encryptedDnsEnabled = encryptedDnsEnabled
            return result
        }
    }

    private class FakeWebRtcLeakCheckerPort : WebRtcLeakCheckerPort {
        val result = category("webrtc")
        var calls = 0
        var webRtcProtectionEnabled: Boolean? = null

        override suspend fun check(webRtcProtectionEnabled: Boolean): CategoryResult {
            calls += 1
            this.webRtcProtectionEnabled = webRtcProtectionEnabled
            return result
        }
    }

    private class FakeTlsFingerprintCheckerPort : TlsFingerprintCheckerPort {
        val result = category("tls")
        var calls = 0
        var tlsFingerprintProfile: String? = null

        override suspend fun check(tlsFingerprintProfile: String): CategoryResult {
            calls += 1
            this.tlsFingerprintProfile = tlsFingerprintProfile
            return result
        }
    }

    private class FakeTimingAnalysisCheckerPort : TimingAnalysisCheckerPort {
        val result = category("timing")
        var calls = 0

        override suspend fun check(): CategoryResult {
            calls += 1
            return result
        }
    }

    private class FakeDetectionVerdictEvaluator : DetectionVerdictEvaluator {
        var geoIp: CategoryResult? = null
        var locationSignals: CategoryResult? = null
        var bypassResult: BypassResult? = null

        override fun evaluate(
            geoIp: CategoryResult,
            directSigns: CategoryResult,
            indirectSigns: CategoryResult,
            locationSignals: CategoryResult,
            bypassResult: BypassResult,
        ): Verdict {
            this.geoIp = geoIp
            this.locationSignals = locationSignals
            this.bypassResult = bypassResult
            return Verdict.NEEDS_REVIEW
        }
    }
}

private fun category(name: String): CategoryResult =
    CategoryResult(
        name = name,
        detected = false,
        findings = listOf(Finding("$name finding")),
    )

private fun bypass(): BypassResult =
    BypassResult(
        proxyEndpoint = null,
        directIp = null,
        proxyIp = null,
        xrayApiScanResult = null,
        findings = listOf(Finding("bypass finding")),
        detected = false,
    )
