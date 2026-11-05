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
            val initialSettings = appSettingsRepository.snapshot()
            stores.sessionsState.value =
                listOf(
                    diagnosticsSession(
                        id = "audit-session",
                        profileId = "automatic-audit",
                        pathMode = ScanPathMode.RAW_PATH.name,
                        summary = "Audit complete",
                        reportJson =
                            json.encodeToString(
                                com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                                    .serializer(),
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
                                        ).homeStrategyConfigJson(),
                                ).toEngineScanReportWire(),
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
            assertEquals(initialSettings.proxyIp, savedSettings.proxyIp)
            assertEquals(initialSettings.proxyPort, savedSettings.proxyPort)
            assertEquals(initialSettings.noDomain, savedSettings.noDomain)
            assertTrue(savedSettings.desyncUdp)
            assertEquals(initialSettings.hostsMode, savedSettings.hostsMode)
            assertEquals(initialSettings.hostsBlacklist, savedSettings.hostsBlacklist)
            assertTrue(outcome.appliedSettings.any { it.label == "TCP/TLS lane" })
        }

    @Test
    fun `finalizeHomeAudit preserves routing settings outside the measured strategy`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val appSettingsRepository = FakeAppSettingsRepository()
            val initialSettings = appSettingsRepository.snapshot()
            stores.sessionsState.value =
                listOf(
                    diagnosticsSession(
                        id = "warp-audit-session",
                        profileId = "automatic-audit",
                        pathMode = ScanPathMode.RAW_PATH.name,
                        summary = "Audit complete",
                        reportJson =
                            json.encodeToString(
                                com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                                    .serializer(),
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
                                        ).homeStrategyConfigJson(),
                                ).toEngineScanReportWire(),
                            ),
                    ),
                )

            val outcome =
                createHomeWorkflowService(
                    stores = stores,
                    appSettingsRepository = appSettingsRepository,
                ).finalizeHomeAudit("warp-audit-session")

            assertTrue(outcome.actionable)
            assertFalse(outcome.appliedSettings.any { it.label.startsWith("WARP") })
            val savedSettings = appSettingsRepository.snapshot()
            assertEquals(initialSettings.warpEnabled, savedSettings.warpEnabled)
            assertEquals(initialSettings.warpRouteMode, savedSettings.warpRouteMode)
            assertEquals(initialSettings.warpRouteHosts, savedSettings.warpRouteHosts)
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
                                com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                                    .serializer(),
                                strategyAuditReport("capability-session").toEngineScanReportWire(),
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
                                com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                                    .serializer(),
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
                                        ).homeStrategyConfigJson(),
                                ).toEngineScanReportWire(),
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
                                com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                                    .serializer(),
                                resolverOnlyAuditReport("resolver-session").toEngineScanReportWire(),
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
            assertEquals("DNS settings applied", outcome.headline)
            assertEquals("cloudflare", savedSettings.dnsProviderId)
            assertEquals("doh", savedSettings.encryptedDnsProtocol)
            assertEquals("cloudflare-dns.com", savedSettings.encryptedDnsHost)
            assertTrue(outcome.appliedSettings.any { it.label == "Resolver" })
        }

    @Test
    fun `finalizeHomeAudit reports but does not apply low coverage secondary recommendation`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val appSettingsRepository = FakeAppSettingsRepository()
            val initialSettings = appSettingsRepository.snapshot()
            stores.sessionsState.value =
                listOf(
                    diagnosticsSession(
                        id = "strategy-recommendation-session",
                        profileId = "automatic-audit",
                        pathMode = ScanPathMode.RAW_PATH.name,
                        summary = "Strategy recommendation",
                        reportJson =
                            json.encodeToString(
                                com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                                    .serializer(),
                                unappliedStrategyRecommendationAuditReport(
                                    sessionId = "strategy-recommendation-session",
                                ).toEngineScanReportWire(),
                            ),
                    ),
                )

            val outcome =
                createHomeWorkflowService(
                    stores = stores,
                    appSettingsRepository = appSettingsRepository,
                ).finalizeHomeAudit("strategy-recommendation-session")

            assertFalse(outcome.actionable)
            assertEquals("Analysis complete, but no settings were applied", outcome.headline)
            assertEquals("Confidence low (65)", outcome.confidenceSummary)
            assertEquals("Matrix 90% · winners 0%", outcome.coverageSummary)
            assertEquals("Prefer TLS record split on this network", outcome.recommendationSummary)
            assertEquals(StrategyAdequacy.STRATEGY_RECOMMENDED, outcome.strategyAdequacy)
            assertTrue(outcome.appliedSettings.isEmpty())
            assertEquals(initialSettings, appSettingsRepository.snapshot())
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
                                com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                                    .serializer(),
                                strategyAuditReport(
                                    sessionId = "low-confidence-session",
                                    confidenceLevel = StrategyProbeAuditConfidenceLevel.MEDIUM,
                                ).toEngineScanReportWire(),
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
                                com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                                    .serializer(),
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
                                ).toEngineScanReportWire(),
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

    @Test
    fun `Home refuses eligible evidence when the configuration disagrees with the winners`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val initial = repository.snapshot()
            val report =
                strategyAuditReport(
                    "mismatched-strategy",
                    recommendedProxyConfigJson = RipDpiProxyUIPreferences().toNativeConfigJson(),
                ).strategyProbeReport

            val result = DiagnosticsHomeRecommendationApplier(repository).applyValidatedRecommendation(report)

            assertEquals(null, result)
            assertEquals(initial, repository.snapshot())
        }

    @Test
    fun `Home applies and summarizes each independently verified lane`() =
        runTest {
            for (tcpOnly in listOf(true, false)) {
                val report = strategyAuditReport("single-lane")
                val probe = requireNotNull(report.strategyProbeReport)
                val recommendation = requireNotNull(probe.recommendation)
                val partialLane =
                    probe.copy(
                        recommendation =
                            recommendation.copy(
                                tcpCandidateId = recommendation.tcpCandidateId.takeIf { tcpOnly },
                                tcpCandidateLabel = recommendation.tcpCandidateLabel.takeIf { tcpOnly },
                                quicCandidateId = recommendation.quicCandidateId.takeUnless { tcpOnly },
                                quicCandidateLabel = recommendation.quicCandidateLabel.takeUnless { tcpOnly },
                            ),
                    )
                val outcome = homeAuditOutcome(report.copy(strategyProbeReport = partialLane))

                assertTrue(outcome.actionable)
                assertEquals(if (tcpOnly) "TCP hostfake" else "QUIC realistic", outcome.recommendationSummary)
                assertEquals(tcpOnly, outcome.appliedSettings.any { it.label == "TCP/TLS lane" })
                assertEquals(!tcpOnly, outcome.appliedSettings.any { it.label == "QUIC lane" })
            }
        }

    @Test
    fun `Home does not apply strategy from an incomplete audit`() =
        runTest {
            val report = strategyAuditReport("incomplete-audit")
            assertFalse(homeAuditOutcome(report.copy(completionKind = ScanCompletionKind.PARTIAL_RESULTS)).actionable)
            assertFalse(homeAuditOutcome(report, status = "cancelled").actionable)
        }

    private suspend fun homeAuditOutcome(
        report: ScanReport,
        status: String = "completed",
    ): DiagnosticsHomeAuditOutcome {
        val stores = FakeDiagnosticsHistoryStores()
        stores.sessionsState.value =
            listOf(
                diagnosticsSession(
                    id = report.sessionId,
                    profileId = report.profileId,
                    pathMode = report.pathMode.name,
                    status = status,
                    summary = report.summary,
                    reportJson = json.encodeToString(report.toEngineScanReportWire()),
                ),
            )
        return createHomeWorkflowService(stores, FakeAppSettingsRepository()).finalizeHomeAudit(report.sessionId)
    }

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

    private fun unappliedStrategyRecommendationAuditReport(sessionId: String): ScanReport {
        val report =
            strategyAuditReport(
                sessionId = sessionId,
                confidenceLevel = StrategyProbeAuditConfidenceLevel.LOW,
            )
        val strategyProbe = requireNotNull(report.strategyProbeReport)
        val assessment = requireNotNull(strategyProbe.auditAssessment)
        return report.copy(
            summary = "Prefer TLS record split on this network",
            strategyProbeReport =
                strategyProbe.copy(
                    auditAssessment =
                        assessment.copy(
                            coverage =
                                assessment.coverage.copy(
                                    tcpWinnerSucceededTargets = 0,
                                    quicWinnerSucceededTargets = 0,
                                    winnerCoveragePercent = 0,
                                    tcpWinnerCoveragePercent = 0,
                                    quicWinnerCoveragePercent = 0,
                                ),
                        ),
                ),
            strategyRecommendation =
                StrategyRecommendation(
                    triggerOutcomes = listOf("tls_blocked"),
                    recommendedFamily = "tlsrec_split",
                    blockingPattern = "sni_tls_suspect",
                    rationale = "Prefer TLS record split on this network",
                    evidence = listOf("tls_post_client_hello_failure"),
                ),
        )
    }
}
