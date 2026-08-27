package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiChainConfig
import com.poyka.ripdpi.core.RipDpiProtocolConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiQuicConfig
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.DiagnosticsProxyCredentials
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.WifiNetworkIdentityTuple
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.decodedSource
import com.poyka.ripdpi.data.diagnostics.toPolicyJson
import com.poyka.ripdpi.data.strategyFamily
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ExecutionPolicy
import com.poyka.ripdpi.diagnostics.domain.ScanContext
import com.poyka.ripdpi.diagnostics.domain.ScanPlan
import com.poyka.ripdpi.diagnostics.finalization.RawPathSettlementBarrier
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class DiagnosticsStrategyProbeRecommendationPersistenceTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `finalization rejects report identity mismatches before persistence`() =
        runTest {
            val prepared =
                preparedStrategyProbeScan(
                    sessionId = "prepared-session",
                    settings = defaultDiagnosticsAppSettings(),
                    fingerprint = networkFingerprint(ssid = "identity-network"),
                )
            val matching =
                strategyProbeReport(
                    sessionId = prepared.sessionId,
                    proxyConfigJson = validPersistenceProxyConfigJson(),
                    tcpFamily = "split",
                    quicFamily = "quic_burst",
                )
            val mismatches =
                listOf(
                    matching.copy(sessionId = "foreign-session"),
                    matching.copy(profileId = "foreign-profile"),
                    matching.copy(pathMode = ScanPathMode.IN_PATH),
                )

            mismatches.forEach { mismatched ->
                val stores = FakeDiagnosticsHistoryStores()
                val failure =
                    runCatching {
                        scanFinalizationService(stores, TestDiagnosticsHistoryClock()).finalize(
                            prepared,
                            json.encodeToString(mismatched.toEngineScanReportWire()),
                        )
                    }.exceptionOrNull()

                assertTrue(failure is IllegalArgumentException)
                assertNull(stores.getScanSession(prepared.sessionId))
                assertNull(stores.getScanSession(mismatched.sessionId))
            }
        }

    @Test
    fun `in-path finalization persists owned active service authority`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val (prepared, report) = ownedActiveObservationFixture("session-owned-in-path")

            scanFinalizationService(
                stores,
                TestDiagnosticsHistoryClock(),
            ).finalize(
                prepared,
                json.encodeToString(report.toEngineScanReportWire()),
                ownedInPathRouteAtCompletion = true,
            )

            val persisted =
                json.decodeEngineScanReportWire(
                    requireNotNull(stores.getScanSession(prepared.sessionId)?.reportJson),
                )
            val strategy = requireNotNull(persisted.strategyProbeReport)
            val observation = requireNotNull(strategy.activePathObservation)
            assertEquals(StrategyProbeObservationRole.ACTIVE_SERVICE_IN_PATH, observation.role)
            assertEquals(StrategyActivePathAuthority.OWNED_ROUTE_LEASE_AT_SCAN, observation.activePathAuthority)
        }

    @Test
    fun `in-path finalization without terminal route verification cannot grant authority`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val (prepared, report) = ownedActiveObservationFixture("session-unverified-terminal")

            scanFinalizationService(stores, TestDiagnosticsHistoryClock()).finalize(
                prepared,
                json.encodeToString(report.toEngineScanReportWire()),
            )

            val persisted =
                json.decodeEngineScanReportWire(
                    requireNotNull(stores.getScanSession(prepared.sessionId)?.reportJson),
                )
            assertEquals(
                StrategyActivePathAuthority.UNVERIFIED,
                persisted.strategyProbeReport?.activePathObservation?.activePathAuthority,
            )
        }

    @Test
    fun `in-path finalization rejects incoherent active observation authority`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val (prepared, report) = ownedActiveObservationFixture("session-malformed-in-path")
            val strategyProbe = requireNotNull(report.strategyProbeReport)
            val observation = requireNotNull(strategyProbe.activePathObservation)
            val malformed =
                report.copy(
                    strategyProbeReport =
                        strategyProbe.copy(
                            activePathObservation = observation.copy(successfulTargets = 2),
                        ),
                )

            scanFinalizationService(stores, TestDiagnosticsHistoryClock()).finalize(
                prepared,
                json.encodeToString(malformed.toEngineScanReportWire()),
            )

            val persisted =
                json.decodeEngineScanReportWire(
                    requireNotNull(stores.getScanSession(prepared.sessionId)?.reportJson),
                )
            assertEquals(
                StrategyActivePathAuthority.UNVERIFIED,
                persisted.strategyProbeReport?.activePathObservation?.activePathAuthority,
            )
        }

    @Test
    fun `captured terminal authority survives delayed finalization`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val (prepared, report) = ownedActiveObservationFixture("session-route-replaced-at-commit")
            val service =
                scanFinalizationService(
                    stores,
                    TestDiagnosticsHistoryClock(),
                )

            service.finalize(
                prepared,
                json.encodeToString(report.toEngineScanReportWire()),
                ownedInPathRouteAtCompletion = true,
            )

            val persisted =
                json.decodeEngineScanReportWire(
                    requireNotNull(stores.getScanSession(prepared.sessionId)?.reportJson),
                )
            assertEquals(
                StrategyActivePathAuthority.OWNED_ROUTE_LEASE_AT_SCAN,
                persisted.strategyProbeReport?.activePathObservation?.activePathAuthority,
            )
        }

    @Test
    fun `in-path lease cannot synthesize an active observation`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val settings = defaultDiagnosticsAppSettings()
            val prepared =
                preparedStrategyProbeScan(
                    sessionId = "session-no-active-observation",
                    settings = settings,
                    fingerprint = networkFingerprint(ssid = "no-active-observation-network"),
                ).copy(
                    pathMode = ScanPathMode.IN_PATH,
                    inPathRouteLease =
                        DiagnosticsInPathRouteLease(
                            runtimeId = "vpn-runtime",
                            routeGeneration = 8,
                            issuedRevision = 1L,
                            host = "127.0.0.1",
                            port = 19_080,
                            credentials = DiagnosticsProxyCredentials("diagnostics", "bounded-secret"),
                        ),
                )
            val report =
                strategyProbeReport(
                    sessionId = prepared.sessionId,
                    proxyConfigJson = validPersistenceProxyConfigJson(),
                    tcpFamily = "hostfake",
                    quicFamily = "quic_realistic_burst",
                    auditAssessment = auditAssessment(),
                ).copy(pathMode = ScanPathMode.IN_PATH)

            scanFinalizationService(stores, TestDiagnosticsHistoryClock()).finalize(
                prepared,
                json.encodeToString(report.toEngineScanReportWire()),
            )

            val persisted =
                json.decodeEngineScanReportWire(
                    requireNotNull(stores.getScanSession(prepared.sessionId)?.reportJson),
                )
            assertNull(requireNotNull(persisted.strategyProbeReport).activePathObservation)
        }

    @Test
    fun `confirmed quick matrix persists profile caps without raw target names`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val settings = defaultDiagnosticsAppSettings().toBuilder().setNetworkStrategyMemoryEnabled(true).build()
            val fingerprint = networkFingerprint(ssid = "axis-m-network")
            val prepared = preparedStrategyProbeScan("session-axis-m", settings, fingerprint)
            val report =
                strategyProbeReport(
                    sessionId = prepared.sessionId,
                    proxyConfigJson = validPersistenceProxyConfigJson(),
                    tcpFamily = "hostfake",
                    quicFamily = "quic_realistic_burst",
                    auditAssessment = auditAssessment(),
                    connectionConcurrencyAssessment =
                        ConnectionConcurrencyAssessment(
                            verdict = ConnectionConcurrencyVerdict.CONJUNCTION_CONFIRMED,
                            selectedProfileId = "firefox_stable",
                            safeCap = 4,
                            plannedCells = 36,
                            cleanCells = 34,
                            affectedTargets = 2,
                            healthyCapsByProfile = mapOf("firefox_stable" to 4, "safari_stable" to 8),
                        ),
                )
            scanFinalizationService(stores, TestDiagnosticsHistoryClock()).finalize(
                prepared,
                json.encodeToString(report.toEngineScanReportWire()),
            )

            val remembered = stores.rememberedPoliciesState.value.single()
            val policy = requireNotNull(remembered.toPolicyJson())
            assertEquals("firefox_stable", policy.connectionConcurrencyPolicy?.selectedProfileId)
            assertEquals(4, policy.connectionConcurrencyPolicy?.perProfileCaps?.get("firefox_stable"))
            assertEquals(
                "firefox_stable",
                decodeRipDpiProxyUiPreferences(policy.proxyConfigJson)?.fakePackets?.tlsFingerprintProfile,
            )
            val persistedPayload =
                listOfNotNull(
                    remembered.proxyConfigJson,
                    remembered.connectionConcurrencyPolicyJson,
                ).joinToString()
            assertFalse(persistedPayload.contains("youtube.com"))
            assertFalse(persistedPayload.contains("discord.com"))
            assertFalse(persistedPayload.contains("proton.me"))
        }

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
                        proxyConfigJson = validPersistenceProxyConfigJson(),
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
                        proxyConfigJson = validPersistenceProxyConfigJson(),
                        tcpFamily = "split",
                        quicFamily = "quic_burst",
                        auditAssessment = auditAssessment(),
                    ).toEngineScanReportWire(),
                )

            finalizationService.finalize(prepared, reportJson)

            assertTrue(stores.rememberedPoliciesState.value.isEmpty())
            val persistedReport =
                json
                    .decodeEngineScanReportWire(
                        requireNotNull(stores.getScanSession(prepared.sessionId)?.reportJson),
                    ).toScanReport()
            val recommendation = requireNotNull(requireNotNull(persistedReport.strategyProbeReport).recommendation)
            assertEquals("tcp-1", recommendation.tcpCandidateId)
            assertEquals("quic-1", recommendation.quicCandidateId)
            assertEquals("best path", recommendation.rationale)
            assertNull(recommendation.tcpCandidateFamily)
            assertNull(recommendation.quicCandidateFamily)
            assertNull(recommendation.dnsStrategyFamily)
            assertNull(recommendation.dnsStrategyLabel)
            assertNull(recommendation.strategySignature)
        }

    @Test
    fun `transport pivot verdict never persists a TLS strategy recommendation`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val prepared =
                preparedStrategyProbeScan(
                    sessionId = "session-transport-pivot",
                    settings = settings,
                    fingerprint = networkFingerprint(ssid = "pivot-network"),
                )
            val report =
                strategyProbeReport(
                    sessionId = prepared.sessionId,
                    proxyConfigJson = validPersistenceProxyConfigJson(),
                    tcpFamily = "hostfake",
                    quicFamily = "quic_realistic_burst",
                    auditAssessment = auditAssessment(),
                )
            val strategyProbe = requireNotNull(report.strategyProbeReport)
            val pivotReport =
                report.copy(
                    strategyProbeReport =
                        strategyProbe.copy(
                            recommendation =
                                requireNotNull(strategyProbe.recommendation).copy(
                                    transportPivot =
                                        TransportPivotRecommendation(
                                            reasonCode = "confirm_good_dpi_suspected",
                                            preferredFamily = TransportFamily.UDP_QUIC,
                                            viability = TransportPivotViability.CONFIRMED,
                                        ),
                                ),
                        ),
                )

            scanFinalizationService(stores, TestDiagnosticsHistoryClock()).finalize(
                prepared,
                json.encodeToString(pivotReport.toEngineScanReportWire()),
            )

            assertTrue(stores.rememberedPoliciesState.value.isEmpty())
        }

    @Test
    fun `background finalization preserves engine diagnoses and classifier version`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val finalizationService = scanFinalizationService(stores, clock)
            val prepared =
                preparedStrategyProbeScan(
                    sessionId = "session-diagnosis-authority",
                    settings = defaultDiagnosticsAppSettings(),
                    fingerprint = networkFingerprint(ssid = "authority-network"),
                )
            val engineDiagnosis =
                Diagnosis(
                    code = "dns_tampering",
                    summary = "Engine diagnosis should be preserved",
                    evidence = listOf("engine"),
                )
            val reportJson =
                json.encodeToString(
                    strategyProbeReport(
                        sessionId = prepared.sessionId,
                        proxyConfigJson = validPersistenceProxyConfigJson(),
                        tcpFamily = "hostfake",
                        quicFamily = "quic_multi_initial_realistic",
                        auditAssessment = auditAssessment(),
                    ).copy(
                        diagnoses = listOf(engineDiagnosis),
                        classifierVersion = "rust_diag_v2",
                        observations =
                            listOf(
                                ObservationFact(
                                    kind = ObservationKind.DOMAIN,
                                    target = "blocked.example",
                                    domain =
                                        DomainObservationFact(
                                            host = "blocked.example",
                                            httpStatus = HttpProbeStatus.BLOCKPAGE,
                                        ),
                                ),
                            ),
                    ).toEngineScanReportWire(),
                )

            finalizationService.finalize(prepared, reportJson)

            val persistedWire =
                json.decodeEngineScanReportWire(
                    requireNotNull(stores.getScanSession(prepared.sessionId)?.reportJson),
                )
            assertEquals(listOf(engineDiagnosis), persistedWire.diagnoses)
            assertEquals("rust_diag_v2", persistedWire.classifierVersion)
            assertFalse(persistedWire.diagnoses.any { it.code == "http_blockpage_detected" })
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
        networkEdgePreferenceStore =
            com.poyka.ripdpi.data.diagnostics
                .DefaultNetworkEdgePreferenceStore(stores, clock),
        networkDnsPathPreferenceStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
        serverCapabilityStore = FakeServerCapabilityStore(),
        rawPathSettlementBarrier =
            RawPathSettlementBarrier(
                stores,
                stores.rawPathSettlementStore,
                diagnosticsTestJson(),
            ),
        json = diagnosticsTestJson(),
    )

internal fun ownedActiveObservationFixture(sessionId: String): Pair<PreparedDiagnosticsScan, ScanReport> {
    val settings = defaultDiagnosticsAppSettings()
    val prepared =
        preparedStrategyProbeScan(
            sessionId = sessionId,
            settings = settings,
            fingerprint = networkFingerprint(ssid = "owned-in-path-network"),
        ).copy(
            pathMode = ScanPathMode.IN_PATH,
            inPathRouteLease =
                DiagnosticsInPathRouteLease(
                    runtimeId = "vpn-runtime",
                    routeGeneration = 7,
                    issuedRevision = 1L,
                    host = "127.0.0.1",
                    port = 19_080,
                    credentials = DiagnosticsProxyCredentials("diagnostics", "bounded-secret"),
                ),
        )
    val baseReport =
        strategyProbeReport(
            sessionId = prepared.sessionId,
            proxyConfigJson = validPersistenceProxyConfigJson(),
            tcpFamily = "hostfake",
            quicFamily = "quic_realistic_burst",
            auditAssessment = auditAssessment(),
        )
    val report =
        baseReport.copy(
            pathMode = ScanPathMode.IN_PATH,
            strategyProbeReport =
                requireNotNull(baseReport.strategyProbeReport).copy(
                    activePathObservation =
                        StrategyActivePathObservation(
                            role = StrategyProbeObservationRole.ACTIVE_SERVICE_IN_PATH,
                            responseStage = StrategyProbeResponseStage.RESPONSE_OBSERVED,
                            attemptedTargets = 1,
                            routeReachedTargets = 1,
                            responseObservedTargets = 1,
                        ),
                ),
        )
    return prepared to report
}

private fun preparedStrategyProbeScan(
    sessionId: String,
    settings: com.poyka.ripdpi.proto.AppSettings,
    fingerprint: NetworkFingerprint,
): PreparedDiagnosticsScan {
    val contextSnapshot = FakeDiagnosticsContextProvider().captureContextForTest()
    val intent = buildAutomaticProbingIntent(settings)
    val context = buildScanContext(settings, fingerprint, contextSnapshot)
    val plan =
        ScanPlan(
            intent = intent,
            context = context,
            proxyHost = null,
            proxyPort = null,
            dnsTargets = emptyList(),
            probeTasks = emptyList(),
        )
    return PreparedDiagnosticsScan(
        sessionId = sessionId,
        settings = settings,
        pathMode = ScanPathMode.RAW_PATH,
        intent = intent,
        context = context,
        plan = plan,
        requestJson =
            diagnosticsTestJson().encodeToString(
                EngineScanRequestWire.serializer(),
                plan.toEngineScanRequestWire(),
            ),
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
        preScanSnapshot = buildPreScanSnapshot(sessionId),
        preScanContext = buildPreScanContext(sessionId, contextSnapshot),
    )
}

private fun buildAutomaticProbingIntent(settings: com.poyka.ripdpi.proto.AppSettings) =
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

private fun buildScanContext(
    settings: com.poyka.ripdpi.proto.AppSettings,
    fingerprint: NetworkFingerprint,
    contextSnapshot: DiagnosticContextModel,
) = ScanContext(
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

private fun buildPreScanSnapshot(sessionId: String) =
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
    )

private fun buildPreScanContext(
    sessionId: String,
    contextSnapshot: DiagnosticContextModel,
) = DiagnosticContextEntity(
    id = UUID.randomUUID().toString(),
    sessionId = sessionId,
    contextKind = "pre_scan",
    payloadJson =
        diagnosticsTestJson().encodeToString(
            DiagnosticContextModel.serializer(),
            contextSnapshot,
        ),
    capturedAt = 1_000L,
)

private fun strategyProbeReport(
    sessionId: String,
    proxyConfigJson: String,
    tcpFamily: String,
    quicFamily: String,
    auditAssessment: StrategyProbeAuditAssessment? = null,
    connectionConcurrencyAssessment: ConnectionConcurrencyAssessment? = null,
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
                connectionConcurrencyAssessment = connectionConcurrencyAssessment,
                pilotBucketLabels = listOf("control:neutral:success"),
            ),
    )

private fun validPersistenceProxyConfigJson(): String =
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
                udpSteps = listOf(UdpChainStepModel(count = 4)),
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
