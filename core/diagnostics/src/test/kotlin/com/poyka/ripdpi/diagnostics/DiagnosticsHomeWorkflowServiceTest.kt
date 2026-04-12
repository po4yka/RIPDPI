package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiAdaptiveFallbackConfig
import com.poyka.ripdpi.core.RipDpiFakePacketConfig
import com.poyka.ripdpi.core.RipDpiHostsConfig
import com.poyka.ripdpi.core.RipDpiListenConfig
import com.poyka.ripdpi.core.RipDpiProtocolConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiWarpAmneziaConfig
import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.core.RipDpiWarpManualEndpointConfig
import com.poyka.ripdpi.data.EntropyModeCombined
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import com.poyka.ripdpi.data.WarpEndpointSelectionManual
import com.poyka.ripdpi.data.WarpRouteModeRules
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsHomeWorkflowServiceTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `finalizeHomeAudit applies eligible strategy recommendation to saved settings`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val appSettingsRepository = FakeAppSettingsRepository()
            stores.sessionsState.value =
                listOf(
                    diagnosticsSession(
                        id = "audit-session",
                        profileId = "automatic-audit",
                        pathMode = ScanPathMode.RAW_PATH.name,
                        summary = "Audit complete",
                        reportJson =
                            json.encodeToString(
                                ScanReport.serializer(),
                                strategyAuditReport(
                                    sessionId = "audit-session",
                                    recommendedProxyConfigJson =
                                        RipDpiProxyUIPreferences(
                                            listen = RipDpiListenConfig(ip = "10.0.0.2", port = 2080),
                                            protocols = RipDpiProtocolConfig(resolveDomains = false, desyncUdp = true),
                                            hosts =
                                                RipDpiHostsConfig(
                                                    mode = RipDpiHostsConfig.Mode.Blacklist,
                                                    entries = "example.com",
                                                ),
                                        ).toNativeConfigJson(),
                                ),
                            ),
                    ).copy(triggerCurrentFingerprintHash = "fp-audit"),
                )

            val outcome =
                createHomeWorkflowService(
                    stores = stores,
                    appSettingsRepository = appSettingsRepository,
                ).finalizeHomeAudit("audit-session")

            val savedSettings = appSettingsRepository.snapshot()
            assertTrue(outcome.actionable)
            assertEquals("fp-audit", outcome.fingerprintHash)
            assertEquals("10.0.0.2", savedSettings.proxyIp)
            assertEquals(2080, savedSettings.proxyPort)
            assertTrue(savedSettings.noDomain)
            assertTrue(savedSettings.desyncUdp)
            assertEquals("blacklist", savedSettings.hostsMode)
            assertEquals("example.com", savedSettings.hostsBlacklist)
            assertTrue(outcome.appliedSettings.any { it.label == "TCP/TLS lane" })
        }

    @Test
    fun `finalizeHomeAudit includes warp configuration in applied settings summary`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val appSettingsRepository = FakeAppSettingsRepository()
            stores.sessionsState.value =
                listOf(
                    diagnosticsSession(
                        id = "warp-audit-session",
                        profileId = "automatic-audit",
                        pathMode = ScanPathMode.RAW_PATH.name,
                        summary = "Audit complete",
                        reportJson =
                            json.encodeToString(
                                ScanReport.serializer(),
                                strategyAuditReport(
                                    sessionId = "warp-audit-session",
                                    recommendedProxyConfigJson =
                                        RipDpiProxyUIPreferences(
                                            warp =
                                                RipDpiWarpConfig(
                                                    enabled = true,
                                                    routeMode = WarpRouteModeRules,
                                                    routeHosts = "example.com\nexample.org",
                                                    endpointSelectionMode = WarpEndpointSelectionManual,
                                                    manualEndpoint =
                                                        RipDpiWarpManualEndpointConfig(
                                                            host = "engage.cloudflareclient.com",
                                                            port = 2408,
                                                        ),
                                                    amnezia =
                                                        RipDpiWarpAmneziaConfig(
                                                            enabled = true,
                                                            jc = 5,
                                                            jmin = 40,
                                                            jmax = 80,
                                                        ),
                                                ),
                                        ).toNativeConfigJson(),
                                ),
                            ),
                    ),
                )

            val outcome =
                createHomeWorkflowService(
                    stores = stores,
                    appSettingsRepository = appSettingsRepository,
                ).finalizeHomeAudit("warp-audit-session")

            assertTrue(outcome.actionable)
            assertTrue(
                outcome.appliedSettings.any {
                    it.label == "WARP routing" && it.value == "Rules"
                },
            )
            assertTrue(
                outcome.appliedSettings.any {
                    it.label == "WARP hostlist" && it.value == "2 hosts"
                },
            )
            assertTrue(
                outcome.appliedSettings.any {
                    it.label == "WARP endpoint" && it.value == "engage.cloudflareclient.com:2408"
                },
            )
            assertTrue(
                outcome.appliedSettings.any {
                    it.label == "WARP AmneziaWG" && it.value.contains("JC 5")
                },
            )
            val savedSettings = appSettingsRepository.snapshot()
            assertTrue(savedSettings.warpEnabled)
            assertEquals(WarpRouteModeRules, savedSettings.warpRouteMode)
            assertEquals("example.com\nexample.org", savedSettings.warpRouteHosts)
        }

    @Test
    fun `finalizeHomeAudit includes stored capability evidence for the audit fingerprint`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val appSettingsRepository = FakeAppSettingsRepository()
            val capabilityStore = FakeServerCapabilityStore()
            val fingerprint = fingerprint("fp-capability")
            stores.sessionsState.value =
                listOf(
                    diagnosticsSession(
                        id = "capability-session",
                        profileId = "automatic-audit",
                        pathMode = ScanPathMode.RAW_PATH.name,
                        summary = "Audit complete",
                        reportJson =
                            json.encodeToString(
                                ScanReport.serializer(),
                                strategyAuditReport("capability-session"),
                            ),
                    ).copy(triggerCurrentFingerprintHash = fingerprint.scopeKey()),
                )
            capabilityStore.rememberDirectPathObservation(
                fingerprint = fingerprint,
                authority = "video.example",
                observation =
                    com.poyka.ripdpi.data.ServerCapabilityObservation(
                        quicUsable = true,
                        udpUsable = true,
                        fallbackRequired = true,
                    ),
                source = "diagnostics",
                recordedAt = 50L,
            )

            val outcome =
                createHomeWorkflowService(
                    stores = stores,
                    appSettingsRepository = appSettingsRepository,
                    serverCapabilityStore = capabilityStore,
                ).finalizeHomeAudit("capability-session")

            assertEquals(1, outcome.capabilityEvidence.size)
            assertEquals("video.example", outcome.capabilityEvidence.single().authority)
            assertTrue(
                outcome.capabilityEvidence
                    .single()
                    .summary
                    .contains("QUIC usable"),
            )
        }

    @Test
    fun `finalizeHomeAudit includes detection resistance configuration in applied settings summary`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val appSettingsRepository = FakeAppSettingsRepository()
            stores.sessionsState.value =
                listOf(
                    diagnosticsSession(
                        id = "tier4-audit-session",
                        profileId = "automatic-audit",
                        pathMode = ScanPathMode.RAW_PATH.name,
                        summary = "Audit complete",
                        reportJson =
                            json.encodeToString(
                                ScanReport.serializer(),
                                strategyAuditReport(
                                    sessionId = "tier4-audit-session",
                                    recommendedProxyConfigJson =
                                        RipDpiProxyUIPreferences(
                                            fakePackets =
                                                RipDpiFakePacketConfig(
                                                    quicBindLowPort = true,
                                                    quicMigrateAfterHandshake = true,
                                                    entropyMode = EntropyModeCombined,
                                                    entropyPaddingTargetPermil = 3600,
                                                    entropyPaddingMax = 384,
                                                    shannonEntropyTargetPermil = 7900,
                                                    tlsFingerprintProfile = TlsFingerprintProfileChromeStable,
                                                ),
                                            adaptiveFallback =
                                                RipDpiAdaptiveFallbackConfig(
                                                    strategyEvolution = true,
                                                    evolutionEpsilon = 0.2,
                                                ),
                                        ).toNativeConfigJson(),
                                ),
                            ),
                    ),
                )

            val outcome =
                createHomeWorkflowService(
                    stores = stores,
                    appSettingsRepository = appSettingsRepository,
                ).finalizeHomeAudit("tier4-audit-session")

            assertTrue(outcome.actionable)
            assertTrue(
                outcome.appliedSettings.any {
                    it.label == "Traffic morphing" &&
                        it.value == "combined · pad 3600 · shannon 7900"
                },
            )
            assertTrue(
                outcome.appliedSettings.any {
                    it.label == "Morphing budget" && it.value == "384 bytes"
                },
            )
            assertTrue(
                outcome.appliedSettings.any {
                    it.label == "Strategy evolution" && it.value == "Epsilon 0.20"
                },
            )
            assertTrue(
                outcome.appliedSettings.any {
                    it.label == "QUIC resistance" &&
                        it.value == "low-port bind · post-handshake UDP rebind"
                },
            )
        }

    @Test
    fun `finalizeHomeAudit persists resolver recommendation when strategy is not actionable`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val appSettingsRepository = FakeAppSettingsRepository()
            stores.sessionsState.value =
                listOf(
                    diagnosticsSession(
                        id = "resolver-session",
                        profileId = "automatic-audit",
                        pathMode = ScanPathMode.RAW_PATH.name,
                        summary = "Resolver recommendation",
                        reportJson =
                            json.encodeToString(
                                ScanReport.serializer(),
                                resolverOnlyAuditReport("resolver-session"),
                            ),
                    ),
                )

            val outcome =
                createHomeWorkflowService(
                    stores = stores,
                    appSettingsRepository = appSettingsRepository,
                ).finalizeHomeAudit("resolver-session")

            val savedSettings = appSettingsRepository.snapshot()
            assertTrue(outcome.actionable)
            assertEquals("cloudflare", savedSettings.dnsProviderId)
            assertEquals("doh", savedSettings.encryptedDnsProtocol)
            assertEquals("cloudflare-dns.com", savedSettings.encryptedDnsHost)
            assertTrue(outcome.appliedSettings.any { it.label == "Resolver" })
        }

    @Test
    fun `finalizeHomeAudit leaves settings unchanged for low confidence results`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val appSettingsRepository = FakeAppSettingsRepository()
            val initialSettings = appSettingsRepository.snapshot()
            stores.sessionsState.value =
                listOf(
                    diagnosticsSession(
                        id = "low-confidence-session",
                        profileId = "automatic-audit",
                        pathMode = ScanPathMode.RAW_PATH.name,
                        summary = "Low confidence audit",
                        reportJson =
                            json.encodeToString(
                                ScanReport.serializer(),
                                strategyAuditReport(
                                    sessionId = "low-confidence-session",
                                    confidenceLevel = StrategyProbeAuditConfidenceLevel.MEDIUM,
                                ),
                            ),
                    ),
                )

            val outcome =
                createHomeWorkflowService(
                    stores = stores,
                    appSettingsRepository = appSettingsRepository,
                ).finalizeHomeAudit("low-confidence-session")

            assertFalse(outcome.actionable)
            assertEquals(initialSettings, appSettingsRepository.snapshot())
            assertTrue(outcome.appliedSettings.isEmpty())
        }

    @Test
    fun `summarizeVerification marks connectivity issue sessions as failed`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.sessionsState.value =
                listOf(
                    diagnosticsSession(
                        id = "verify-session",
                        profileId = "default",
                        pathMode = ScanPathMode.IN_PATH.name,
                        summary = "Verification complete",
                        reportJson =
                            json.encodeToString(
                                ScanReport.serializer(),
                                ScanReport(
                                    sessionId = "verify-session",
                                    profileId = "default",
                                    pathMode = ScanPathMode.IN_PATH,
                                    startedAt = 10L,
                                    finishedAt = 20L,
                                    summary = "Connectivity is still blocked",
                                    results =
                                        listOf(
                                            ProbeResult(
                                                probeType = "https",
                                                target = "example.com",
                                                outcome = "tcp_timeout",
                                            ),
                                        ),
                                    diagnoses =
                                        listOf(
                                            Diagnosis(
                                                code = "network_connectivity_issue",
                                                summary = "The current path is still blocked.",
                                            ),
                                        ),
                                ),
                            ),
                    ),
                )

            val outcome =
                createHomeWorkflowService(
                    stores = stores,
                    appSettingsRepository = FakeAppSettingsRepository(),
                ).summarizeVerification("verify-session")

            assertFalse(outcome.success)
            assertEquals("The current path is still blocked.", outcome.detail)
        }

    private fun createHomeWorkflowService(
        stores: FakeDiagnosticsHistoryStores,
        appSettingsRepository: FakeAppSettingsRepository,
        networkFingerprintProvider: MutableNetworkFingerprintProvider = MutableNetworkFingerprintProvider(),
        serverCapabilityStore: FakeServerCapabilityStore = FakeServerCapabilityStore(),
    ): DefaultDiagnosticsHomeWorkflowService {
        val resolverActions =
            DefaultDiagnosticsResolverActions(
                appSettingsRepository = appSettingsRepository,
                recommendationStore = DiagnosticsRecommendationStore(stores, json),
                networkFingerprintProvider = networkFingerprintProvider,
                networkDnsPathPreferenceStore =
                    DefaultNetworkDnsPathPreferenceStore(stores, TestDiagnosticsHistoryClock()),
                resolverOverrideStore = FakeResolverOverrideStore(),
            )
        return DefaultDiagnosticsHomeWorkflowService(
            appSettingsRepository = appSettingsRepository,
            scanRecordStore = stores,
            artifactQueryStore = stores,
            networkFingerprintProvider = networkFingerprintProvider,
            serverCapabilityStore = serverCapabilityStore,
            resolverActions = resolverActions,
            json = json,
        )
    }

    private fun strategyAuditReport(
        sessionId: String,
        recommendedProxyConfigJson: String =
            RipDpiProxyUIPreferences(
                listen = RipDpiListenConfig(ip = "127.0.0.1", port = 1088),
            ).toNativeConfigJson(),
        confidenceLevel: StrategyProbeAuditConfidenceLevel = StrategyProbeAuditConfidenceLevel.HIGH,
    ): ScanReport =
        ScanReport(
            sessionId = sessionId,
            profileId = "automatic-audit",
            pathMode = ScanPathMode.RAW_PATH,
            startedAt = 10L,
            finishedAt = 20L,
            summary = "Audit complete",
            strategyProbeReport =
                StrategyProbeReport(
                    suiteId = "full_matrix_v1",
                    tcpCandidates =
                        listOf(
                            StrategyProbeCandidateSummary(
                                id = "tcp-split",
                                label = "TCP split",
                                family = "split",
                                outcome = "ok",
                                rationale = "TCP path worked",
                                succeededTargets = 3,
                                totalTargets = 4,
                                weightedSuccessScore = 75,
                                totalWeight = 100,
                                qualityScore = 80,
                            ),
                        ),
                    quicCandidates =
                        listOf(
                            StrategyProbeCandidateSummary(
                                id = "quic-fake",
                                label = "QUIC fake",
                                family = "fake",
                                outcome = "ok",
                                rationale = "QUIC path worked",
                                succeededTargets = 2,
                                totalTargets = 3,
                                weightedSuccessScore = 66,
                                totalWeight = 100,
                                qualityScore = 70,
                            ),
                        ),
                    recommendation =
                        StrategyProbeRecommendation(
                            tcpCandidateId = "tcp-split",
                            tcpCandidateLabel = "TCP split",
                            quicCandidateId = "quic-fake",
                            quicCandidateLabel = "QUIC fake",
                            rationale = "Best combined result",
                            recommendedProxyConfigJson = recommendedProxyConfigJson,
                        ),
                    auditAssessment =
                        StrategyProbeAuditAssessment(
                            coverage =
                                StrategyProbeAuditCoverage(
                                    tcpCandidatesPlanned = 1,
                                    tcpCandidatesExecuted = 1,
                                    tcpCandidatesSkipped = 0,
                                    tcpCandidatesNotApplicable = 0,
                                    quicCandidatesPlanned = 1,
                                    quicCandidatesExecuted = 1,
                                    quicCandidatesSkipped = 0,
                                    quicCandidatesNotApplicable = 0,
                                    tcpWinnerSucceededTargets = 3,
                                    tcpWinnerTotalTargets = 4,
                                    quicWinnerSucceededTargets = 2,
                                    quicWinnerTotalTargets = 3,
                                    matrixCoveragePercent = 90,
                                    winnerCoveragePercent = 80,
                                    tcpWinnerCoveragePercent = 75,
                                    quicWinnerCoveragePercent = 66,
                                ),
                            confidence =
                                StrategyProbeAuditConfidence(
                                    level = confidenceLevel,
                                    score = if (confidenceLevel == StrategyProbeAuditConfidenceLevel.HIGH) 92 else 65,
                                    rationale = "Sufficient evidence",
                                ),
                        ),
                ),
        )

    private fun fingerprint(scopeKey: String) =
        com.poyka.ripdpi.data.NetworkFingerprint(
            transport = "wifi",
            networkValidated = true,
            captivePortalDetected = false,
            privateDnsMode = "system",
            dnsServers = emptyList(),
            wifi =
                com.poyka.ripdpi.data
                    .WifiNetworkIdentityTuple(ssid = scopeKey),
        )

    private fun resolverOnlyAuditReport(sessionId: String): ScanReport =
        ScanReport(
            sessionId = sessionId,
            profileId = "automatic-audit",
            pathMode = ScanPathMode.RAW_PATH,
            startedAt = 10L,
            finishedAt = 20L,
            summary = "Use encrypted DNS",
            results =
                listOf(
                    ProbeResult(
                        probeType = "dns",
                        target = "blocked.example",
                        outcome = "dns_blocked",
                    ),
                ),
            resolverRecommendation =
                ResolverRecommendation(
                    triggerOutcome = "dns_blocked",
                    selectedResolverId = "cloudflare",
                    selectedProtocol = "doh",
                    selectedEndpoint = "https://cloudflare-dns.com/dns-query",
                    selectedBootstrapIps = listOf("1.1.1.1"),
                    selectedHost = "cloudflare-dns.com",
                    selectedPort = 443,
                    selectedTlsServerName = "cloudflare-dns.com",
                    selectedDohUrl = "https://cloudflare-dns.com/dns-query",
                    rationale = "Use encrypted DNS",
                    persistable = true,
                ),
        )
}
