package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiChainConfig
import com.poyka.ripdpi.core.RipDpiProtocolConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiQuicConfig
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.WifiNetworkIdentityTuple
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.decodedSource
import com.poyka.ripdpi.data.strategyFamily
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ExecutionPolicy
import com.poyka.ripdpi.diagnostics.domain.ScanContext
import com.poyka.ripdpi.diagnostics.domain.ScanPlan
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class DiagnosticsStrategyProbeRecommendationPersistenceTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `background finalization remembers validated recommendation with matching families and signature`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val fingerprint = networkFingerprint(ssid = "validated-network")
            val finalizationService = scanFinalizationService(stores, clock)
            val activeDns = settings.activeDnsSettings()
            val prepared =
                preparedStrategyProbeScan(
                    sessionId = "session-valid",
                    settings = settings,
                    fingerprint = fingerprint,
                )
            val reportJson =
                json.encodeToString(
                    strategyProbeReport(
                        sessionId = prepared.sessionId,
                        proxyConfigJson = validRecommendedProxyConfigJson(),
                        tcpFamily = "hostfake",
                        quicFamily = "quic_realistic_burst",
                        auditAssessment = auditAssessment(),
                    ).toEngineScanReportWire(),
                )

            finalizationService.finalize(prepared, reportJson)

            val remembered = stores.rememberedPoliciesState.value.single()
            assertEquals(fingerprint.scopeKey(), remembered.fingerprintHash)
            assertEquals("hostfake", remembered.winningTcpStrategyFamily)
            assertEquals("quic_realistic_burst", remembered.winningQuicStrategyFamily)
            assertEquals(
                RememberedNetworkPolicySource.AUTOMATIC_PROBING_BACKGROUND,
                remembered.decodedSource(),
            )
            val signatureJson = requireNotNull(remembered.strategySignatureJson)
            val signature = json.decodeFromString<BypassStrategySignature>(signatureJson)
            assertEquals("hostfake", signature.tcpStrategyFamily)
            assertEquals("quic_realistic_burst", signature.quicStrategyFamily)
            assertEquals(activeDns.strategyFamily(), signature.dnsStrategyFamily)
        }

    @Test
    fun `background finalization skips remembering mismatched recommendation and keeps raw report`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val finalizationService = scanFinalizationService(stores, clock)
            val prepared =
                preparedStrategyProbeScan(
                    sessionId = "session-invalid",
                    settings = settings,
                    fingerprint = networkFingerprint(ssid = "invalid-network"),
                )
            val reportJson =
                json.encodeToString(
                    strategyProbeReport(
                        sessionId = prepared.sessionId,
                        proxyConfigJson = validRecommendedProxyConfigJson(),
                        tcpFamily = "split",
                        quicFamily = "quic_burst",
                        auditAssessment = auditAssessment(),
                    ).toEngineScanReportWire(),
                )

            finalizationService.finalize(prepared, reportJson)

            assertTrue(stores.rememberedPoliciesState.value.isEmpty())
            val persistedReport =
                json
                    .decodeEngineScanReportWireCompat(
                        requireNotNull(stores.getScanSession(prepared.sessionId)?.reportJson),
                    ).toLegacyScanReportCompat()
            val recommendation = requireNotNull(persistedReport.strategyProbeReport).recommendation
            assertEquals("tcp-1", recommendation.tcpCandidateId)
            assertEquals("quic-1", recommendation.quicCandidateId)
            assertEquals("best path", recommendation.rationale)
            assertNull(recommendation.tcpCandidateFamily)
            assertNull(recommendation.quicCandidateFamily)
            assertNull(recommendation.dnsStrategyFamily)
            assertNull(recommendation.dnsStrategyLabel)
            assertNull(recommendation.strategySignature)
        }
}

private fun scanFinalizationService(
    stores: FakeDiagnosticsHistoryStores,
    clock: TestDiagnosticsHistoryClock,
): ScanFinalizationService =
    ScanFinalizationService(
        context = TestContext(),
        scanRecordStore = stores,
        artifactWriteStore = stores,
        networkMetadataProvider = FakeNetworkMetadataProvider(),
        networkFingerprintProvider = MutableNetworkFingerprintProvider(),
        diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
        serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
        resolverOverrideStore = FakeResolverOverrideStore(),
        rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
        networkDnsPathPreferenceStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
        findingProjector = DiagnosticsFindingProjector(),
        json = diagnosticsTestJson(),
    )

private fun preparedStrategyProbeScan(
    sessionId: String,
    settings: com.poyka.ripdpi.proto.AppSettings,
    fingerprint: NetworkFingerprint,
): PreparedDiagnosticsScan {
    val contextSnapshot = FakeDiagnosticsContextProvider().captureContextForTest()
    val intent =
        DiagnosticsIntent(
            profileId = "automatic-probing",
            displayName = "Automatic probing",
            settings = settings,
            kind = ScanKind.STRATEGY_PROBE,
            family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
            regionTag = null,
            executionPolicy =
                ExecutionPolicy(
                    manualOnly = false,
                    allowBackground = true,
                    requiresRawPath = true,
                    probePersistencePolicy = ProbePersistencePolicy.BACKGROUND_ONLY,
                ),
            packRefs = emptyList(),
            domainTargets = emptyList(),
            dnsTargets = emptyList(),
            tcpTargets = emptyList(),
            quicTargets = emptyList(),
            serviceTargets = emptyList(),
            circumventionTargets = emptyList(),
            throughputTargets = emptyList(),
            whitelistSni = emptyList(),
            telegramTarget = null,
            strategyProbe = StrategyProbeRequest(suiteId = "quick_v1"),
            requestedPathMode = ScanPathMode.RAW_PATH,
        )
    val context =
        ScanContext(
            settings = settings,
            pathMode = ScanPathMode.RAW_PATH,
            networkFingerprint = fingerprint,
            preferredDnsPath = null,
            networkSnapshot = null,
            serviceMode = Mode.VPN.name,
            contextSnapshot = contextSnapshot,
            approachSnapshot =
                createStoredApproachSnapshot(
                    json = diagnosticsTestJson(),
                    settings = settings,
                    profile = null,
                    context = contextSnapshot,
                ),
        )
    return PreparedDiagnosticsScan(
        sessionId = sessionId,
        settings = settings,
        pathMode = ScanPathMode.RAW_PATH,
        intent = intent,
        context = context,
        plan =
            ScanPlan(
                intent = intent,
                context = context,
                proxyHost = null,
                proxyPort = null,
                dnsTargets = emptyList(),
                probeTasks = emptyList(),
            ),
        requestJson = "{}",
        scanOrigin = DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
        launchTrigger = null,
        exposeProgress = false,
        registerActiveBridge = false,
        networkFingerprint = fingerprint,
        preferredDnsPath = null,
        initialSession =
            diagnosticsSession(
                id = sessionId,
                profileId = intent.profileId,
                pathMode = ScanPathMode.RAW_PATH.name,
                summary = "running",
                status = "running",
                reportJson = null,
            ),
        preScanSnapshot =
            NetworkSnapshotEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                snapshotKind = "pre_scan",
                payloadJson =
                    diagnosticsTestJson().encodeToString(
                        NetworkSnapshotModel.serializer(),
                        networkSnapshotModelForTest(),
                    ),
                capturedAt = 1_000L,
            ),
        preScanContext =
            DiagnosticContextEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                contextKind = "pre_scan",
                payloadJson =
                    diagnosticsTestJson().encodeToString(
                        DiagnosticContextModel.serializer(),
                        contextSnapshot,
                    ),
                capturedAt = 1_000L,
            ),
    )
}

private fun strategyProbeReport(
    sessionId: String,
    proxyConfigJson: String,
    tcpFamily: String,
    quicFamily: String,
    auditAssessment: StrategyProbeAuditAssessment? = null,
): ScanReport =
    ScanReport(
        sessionId = sessionId,
        profileId = "automatic-probing",
        pathMode = ScanPathMode.RAW_PATH,
        startedAt = 10L,
        finishedAt = 20L,
        summary = "strategy probe",
        strategyProbeReport =
            StrategyProbeReport(
                suiteId = "quick_v1",
                tcpCandidates =
                    listOf(
                        StrategyProbeCandidateSummary(
                            id = "tcp-1",
                            label = "TCP candidate",
                            family = tcpFamily,
                            outcome = "success",
                            rationale = "best",
                            succeededTargets = 1,
                            totalTargets = 1,
                            weightedSuccessScore = 10,
                            totalWeight = 10,
                            qualityScore = 10,
                        ),
                    ),
                quicCandidates =
                    listOf(
                        StrategyProbeCandidateSummary(
                            id = "quic-1",
                            label = "QUIC candidate",
                            family = quicFamily,
                            outcome = "success",
                            rationale = "best",
                            succeededTargets = 1,
                            totalTargets = 1,
                            weightedSuccessScore = 10,
                            totalWeight = 10,
                            qualityScore = 10,
                        ),
                    ),
                recommendation =
                    StrategyProbeRecommendation(
                        tcpCandidateId = "tcp-1",
                        tcpCandidateLabel = "TCP candidate",
                        quicCandidateId = "quic-1",
                        quicCandidateLabel = "QUIC candidate",
                        rationale = "best path",
                        recommendedProxyConfigJson = proxyConfigJson,
                    ),
                auditAssessment = auditAssessment,
            ),
    )

private fun validRecommendedProxyConfigJson(): String =
    RipDpiProxyUIPreferences(
        protocols = RipDpiProtocolConfig(desyncUdp = true),
        chains =
            RipDpiChainConfig(
                tcpSteps =
                    listOf(
                        TcpChainStepModel(
                            kind = TcpChainStepKind.HostFake,
                            marker = "midhost+1",
                        ),
                    ),
            ),
        quic = RipDpiQuicConfig(fakeProfile = "realistic_initial"),
    ).toNativeConfigJson()

private fun networkFingerprint(ssid: String): NetworkFingerprint =
    NetworkFingerprint(
        transport = "wifi",
        networkValidated = true,
        captivePortalDetected = false,
        privateDnsMode = "system",
        dnsServers = listOf("1.1.1.1"),
        wifi =
            WifiNetworkIdentityTuple(
                ssid = ssid,
                bssid = "aa:bb:cc:dd:ee:ff",
                gateway = "192.0.2.1",
            ),
    )

private fun auditAssessment(): StrategyProbeAuditAssessment =
    StrategyProbeAuditAssessment(
        dnsShortCircuited = false,
        coverage =
            StrategyProbeAuditCoverage(
                tcpCandidatesPlanned = 2,
                tcpCandidatesExecuted = 2,
                tcpCandidatesSkipped = 0,
                tcpCandidatesNotApplicable = 0,
                quicCandidatesPlanned = 2,
                quicCandidatesExecuted = 2,
                quicCandidatesSkipped = 0,
                quicCandidatesNotApplicable = 0,
                tcpWinnerSucceededTargets = 1,
                tcpWinnerTotalTargets = 1,
                quicWinnerSucceededTargets = 1,
                quicWinnerTotalTargets = 1,
                matrixCoveragePercent = 100,
                winnerCoveragePercent = 100,
            ),
        confidence =
            StrategyProbeAuditConfidence(
                level = StrategyProbeAuditConfidenceLevel.HIGH,
                score = 100,
                rationale = "Matrix coverage and winner strength are consistent",
            ),
    )
