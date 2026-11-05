package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiListenConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import com.poyka.ripdpi.diagnostics.application.DefaultDiagnosticsResolverActions
import com.poyka.ripdpi.diagnostics.application.DiagnosticsRecommendationStore

internal fun createHomeWorkflowService(
    stores: FakeDiagnosticsHistoryStores,
    appSettingsRepository: FakeAppSettingsRepository,
    networkFingerprintProvider: MutableNetworkFingerprintProvider = MutableNetworkFingerprintProvider(),
    serverCapabilityStore: FakeServerCapabilityStore = FakeServerCapabilityStore(),
): DefaultDiagnosticsHomeWorkflowService {
    val json = diagnosticsTestJson()
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
        scanRecordStore = stores,
        artifactQueryStore = stores,
        networkFingerprintProvider = networkFingerprintProvider,
        recommendationApplier = DiagnosticsHomeRecommendationApplier(appSettingsRepository),
        resolverActionCoordinator = DiagnosticsHomeResolverActionCoordinator(resolverActions),
        capabilityEvidenceSummarizer = DiagnosticsHomeCapabilityEvidenceSummarizer(serverCapabilityStore),
        auditOutcomeBuilder = DiagnosticsHomeAuditOutcomeBuilder(),
        json = json,
    )
}

internal fun strategyAuditReport(
    sessionId: String,
    recommendedProxyConfigJson: String =
        RipDpiProxyUIPreferences(
            listen = RipDpiListenConfig(ip = "127.0.0.1", port = 1088),
        ).homeStrategyConfigJson(),
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
                            id = "tcp-hostfake",
                            label = "TCP hostfake",
                            family = "hostfake",
                            outcome = "ok",
                            proxyConfigJson = recommendedProxyConfigJson,
                            executionEvidenceComplete = true,
                            runtimeTerminalStatus = StrategyProbeRuntimeTerminalStatus.CLEAN_SHUTDOWN,
                            rationale = "TCP path worked",
                            succeededTargets = 3,
                            totalTargets = 4,
                            weightedSuccessScore = 75,
                            totalWeight = 100,
                            qualityScore = 80,
                            domainOutcomes =
                                listOf(
                                    StrategyProbeDomainOutcome(
                                        domain = "control.example",
                                        succeeded = true,
                                        isControl = true,
                                    ),
                                ),
                        ),
                    ),
                quicCandidates =
                    listOf(
                        StrategyProbeCandidateSummary(
                            id = "quic-realistic",
                            label = "QUIC realistic",
                            family = "quic_realistic_burst",
                            outcome = "ok",
                            proxyConfigJson = recommendedProxyConfigJson,
                            executionEvidenceComplete = true,
                            runtimeTerminalStatus = StrategyProbeRuntimeTerminalStatus.CLEAN_SHUTDOWN,
                            rationale = "QUIC path worked",
                            succeededTargets = 2,
                            totalTargets = 3,
                            weightedSuccessScore = 66,
                            totalWeight = 100,
                            qualityScore = 70,
                            domainOutcomes =
                                listOf(
                                    StrategyProbeDomainOutcome(
                                        domain = "control.example",
                                        succeeded = true,
                                        isControl = true,
                                    ),
                                ),
                        ),
                    ),
                recommendation =
                    StrategyProbeRecommendation(
                        tcpCandidateId = "tcp-hostfake",
                        tcpCandidateLabel = "TCP hostfake",
                        quicCandidateId = "quic-realistic",
                        quicCandidateLabel = "QUIC realistic",
                        rationale = "Best combined result",
                        recommendedProxyConfigJson = recommendedProxyConfigJson,
                    ),
                auditAssessment = strategyAuditAssessment(confidenceLevel),
                pilotBucketLabels = listOf("control:neutral:success"),
            ),
    )

private fun strategyAuditAssessment(confidenceLevel: StrategyProbeAuditConfidenceLevel) =
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
    )

internal fun RipDpiProxyUIPreferences.homeStrategyConfigJson(): String {
    val measured = requireNotNull(decodeRipDpiProxyUiPreferences(validRecommendedProxyConfigJson()))
    return RipDpiProxyUIPreferences(
        listen = listen,
        hosts = hosts,
        warp = warp,
        fakePackets = fakePackets,
        adaptiveFallback = adaptiveFallback,
        protocols = protocols.copy(desyncUdp = true),
        chains = measured.chains,
        quic = measured.quic,
    ).toNativeConfigJson()
}
