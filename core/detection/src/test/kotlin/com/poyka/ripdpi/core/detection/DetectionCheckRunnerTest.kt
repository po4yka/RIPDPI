package com.poyka.ripdpi.core.detection

import android.content.Context
import com.poyka.ripdpi.core.detection.checker.BypassChecker
import com.poyka.ripdpi.core.detection.consensus.IpConsensusResult
import com.poyka.ripdpi.data.diagnostics.DetectionResolverConfig
import com.poyka.ripdpi.data.diagnostics.DetectionResolverMode
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
            ports.location.result =
                category("location").copy(
                    findings = listOf(Finding("Network MCC: 250"), Finding("network_mcc_ru:true")),
                )
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
                            includeIcmpSpoofingCheck = true,
                            includeRttTriangulationCheck = true,
                            includeCallTransportCheck = true,
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
            assertEquals("icmp", result.icmpSpoofing?.category?.name)
            assertEquals("ip comparison", result.ipComparison?.category?.name)
            assertEquals("rtt", result.rttTriangulation?.category?.name)
            assertEquals("native signs", result.nativeSigns?.category?.name)
            assertEquals("call transport", result.callTransport?.category?.name)
            assertEquals(setOf(1080), ports.bypass.excludePorts)
            assertEquals("com.example.proxy", ports.direct.excludePackage)
            assertEquals(true, ports.dns.encryptedDnsEnabled)
            assertEquals(true, ports.webRtc.webRtcProtectionEnabled)
            assertEquals("firefox", ports.tls.tlsFingerprintProfile)
            assertEquals(false, ports.icmp.homeRoutedRoaming)
            assertEquals("RU", ports.rtt.homeCountryIso)
            assertSame(ports.geo.result, ports.verdict.geoIp)
            assertSame(ports.bypass.result, ports.verdict.bypassResult)
            assertSame(ports.ipComparison.result, ports.verdict.ipComparison)
            assertSame(ports.nativeSigns.result, ports.verdict.nativeSigns)
            assertEquals(1, ports.callTransport.calls)
            assertNotNull(result.ipConsensus)
            assertSame(result.ipConsensus, ports.verdict.ipConsensus)
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
                            includeIcmpSpoofingCheck = false,
                            includeIpComparisonCheck = false,
                            includeRttTriangulationCheck = false,
                            includeNativeSignsCheck = false,
                            includeCallTransportCheck = false,
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
            assertNull(result.icmpSpoofing)
            assertNull(result.ipComparison)
            assertNull(result.rttTriangulation)
            assertNull(result.nativeSigns)
            assertNull(result.callTransport)
            assertEquals(0, ports.location.calls)
            assertEquals(0, ports.bypass.calls)
            assertEquals(0, ports.dns.calls)
            assertEquals(0, ports.webRtc.calls)
            assertEquals(0, ports.tls.calls)
            assertEquals(0, ports.timing.calls)
            assertEquals(0, ports.icmp.calls)
            assertEquals(0, ports.ipComparison.calls)
            assertEquals(0, ports.rtt.calls)
            assertEquals(0, ports.nativeSigns.calls)
            assertEquals(0, ports.callTransport.calls)
            assertNotNull(ports.verdict.locationSignals)
            assertNotNull(ports.verdict.bypassResult)
        }

    @Test
    fun `runner passes home routed roaming to icmp checker from location findings`() =
        runTest {
            val ports = FakeDetectionPorts()
            ports.location.result =
                category("location").copy(
                    findings =
                        listOf(
                            Finding("network_mcc_ru:true"),
                            Finding("SIM MCC: 262 (DE)"),
                            Finding("Roaming: yes"),
                        ),
                )

            ports
                .newRunner()
                .run(
                    context = RuntimeEnvironment.getApplication(),
                    config = DetectionRunnerConfig(includeIcmpSpoofingCheck = true),
                )

            assertEquals(true, ports.icmp.homeRoutedRoaming)
        }

    @Test
    fun `runner passes resolver config to network-backed checkers`() =
        runTest {
            val ports = FakeDetectionPorts()
            val resolverConfig =
                DetectionResolverConfig(
                    resolverMode = DetectionResolverMode.DOH,
                    directDnsServers = listOf("9.9.9.9"),
                    dohBootstrapIps = listOf("1.1.1.1"),
                )

            ports
                .newRunner()
                .run(
                    context = RuntimeEnvironment.getApplication(),
                    config =
                        DetectionRunnerConfig(
                            includeCdnPullingCheck = true,
                            resolverConfig = resolverConfig,
                        ),
                )

            assertEquals(resolverConfig, ports.geo.resolverConfig)
            assertEquals(resolverConfig, ports.ipComparison.resolverConfig)
            assertEquals(resolverConfig, ports.cdnPulling.resolverConfig)
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
        val icmp = FakeIcmpSpoofingCheckerPort()
        val ipComparison = FakeIpComparisonCheckerPort()
        val rtt = FakeRttTriangulationCheckerPort()
        val cdnPulling = FakeCdnPullingCheckerPort()
        val nativeSigns = FakeNativeSignsCheckerPort()
        val callTransport = FakeCallTransportCheckerPort()
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
                icmpSpoofingChecker = icmp,
                ipComparisonChecker = ipComparison,
                rttTriangulationChecker = rtt,
                cdnPullingChecker = cdnPulling,
                nativeSignsChecker = nativeSigns,
                callTransportChecker = callTransport,
                verdictEvaluator = verdict,
            )
    }

    private class FakeGeoIpCheckerPort : GeoIpCheckerPort {
        val result = category("geo")
        var resolverConfig: DetectionResolverConfig? = null

        override suspend fun check(resolverConfig: DetectionResolverConfig): CategoryResult {
            this.resolverConfig = resolverConfig
            return result
        }
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
        var result = category("location")
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
            options: BypassScanOptions,
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

    private class FakeIcmpSpoofingCheckerPort : IcmpSpoofingCheckerPort {
        val result =
            IcmpSpoofingResult(
                state = IcmpSpoofingState.OK,
                category = category("icmp"),
            )
        var calls = 0
        var homeRoutedRoaming: Boolean? = null

        override suspend fun check(homeRoutedRoaming: Boolean): IcmpSpoofingResult {
            calls += 1
            this.homeRoutedRoaming = homeRoutedRoaming
            return result
        }
    }

    private class FakeIpComparisonCheckerPort : IpComparisonCheckerPort {
        val result =
            IpComparisonResult(
                category = category("ip comparison"),
                endpoints = emptyList(),
                ruReflectedIps = emptySet(),
                nonRuReflectedIps = emptySet(),
            )
        var calls = 0
        var resolverConfig: DetectionResolverConfig? = null

        override suspend fun check(resolverConfig: DetectionResolverConfig): IpComparisonResult {
            calls += 1
            this.resolverConfig = resolverConfig
            return result
        }
    }

    private class FakeRttTriangulationCheckerPort : RttTriangulationCheckerPort {
        val result =
            RttTriangulationResult(
                state = RttTriangulationState.OK,
                category = category("rtt"),
            )
        var calls = 0
        var homeCountryIso: String? = null

        override suspend fun check(homeCountryIso: String?): RttTriangulationResult {
            calls += 1
            this.homeCountryIso = homeCountryIso
            return result
        }
    }

    private class FakeCdnPullingCheckerPort : CdnPullingCheckerPort {
        val result =
            CdnPullingResult(
                category = category("cdn pulling"),
                endpoints = emptyList(),
            )
        var calls = 0
        var resolverConfig: DetectionResolverConfig? = null

        override suspend fun check(
            enabled: Boolean,
            resolverConfig: DetectionResolverConfig,
        ): CdnPullingResult {
            calls += 1
            this.resolverConfig = resolverConfig
            return result
        }
    }

    private class FakeNativeSignsCheckerPort : NativeSignsCheckerPort {
        val result =
            NativeSignsResult(
                category = category("native signs"),
            )
        var calls = 0

        override fun check(enabled: Boolean): NativeSignsResult {
            calls += 1
            return result
        }
    }

    private class FakeCallTransportCheckerPort : CallTransportCheckerPort {
        val result =
            CallTransportResult(
                category = category("call transport"),
            )
        var calls = 0

        override suspend fun check(
            proxyEndpoint: com.poyka.ripdpi.core.detection.probe.ProxyEndpoint?,
        ): CallTransportResult {
            calls += 1
            return result
        }
    }

    private class FakeDetectionVerdictEvaluator : DetectionVerdictEvaluator {
        var geoIp: CategoryResult? = null
        var locationSignals: CategoryResult? = null
        var bypassResult: BypassResult? = null
        var icmpSpoofing: IcmpSpoofingResult? = null
        var ipComparison: IpComparisonResult? = null
        var rttTriangulation: RttTriangulationResult? = null
        var cdnPulling: CdnPullingResult? = null
        var ipConsensus: IpConsensusResult? = null
        var nativeSigns: NativeSignsResult? = null

        override fun evaluate(
            geoIp: CategoryResult,
            directSigns: CategoryResult,
            indirectSigns: CategoryResult,
            locationSignals: CategoryResult,
            bypassResult: BypassResult,
            icmpSpoofing: IcmpSpoofingResult?,
            ipComparison: IpComparisonResult?,
            rttTriangulation: RttTriangulationResult?,
            cdnPulling: CdnPullingResult?,
            ipConsensus: IpConsensusResult?,
            nativeSigns: NativeSignsResult?,
        ): Verdict {
            this.geoIp = geoIp
            this.locationSignals = locationSignals
            this.bypassResult = bypassResult
            this.icmpSpoofing = icmpSpoofing
            this.ipComparison = ipComparison
            this.rttTriangulation = rttTriangulation
            this.cdnPulling = cdnPulling
            this.ipConsensus = ipConsensus
            this.nativeSigns = nativeSigns
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
