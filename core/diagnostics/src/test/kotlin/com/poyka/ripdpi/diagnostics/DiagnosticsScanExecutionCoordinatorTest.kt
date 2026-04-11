package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiChainConfig
import com.poyka.ripdpi.core.RipDpiProtocolConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiQuicConfig
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ExecutionPolicy
import com.poyka.ripdpi.diagnostics.domain.ScanContext
import com.poyka.ripdpi.diagnostics.domain.ScanPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsScanExecutionCoordinatorTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `completed execution applies temporary override and remembers preferred path`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val resolverOverrideStore = FakeResolverOverrideStore()
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
            val networkFingerprintProvider = FakeNetworkFingerprintProvider()
            val preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock)
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    resolverOverrideStore = resolverOverrideStore,
                    networkFingerprintProvider = networkFingerprintProvider,
                    preferredPathStore = preferredPathStore,
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-1",
                    settings =
                        defaultDiagnosticsAppSettings()
                            .toBuilder()
                            .setDnsMode(DnsModePlainUdp)
                            .setDnsIp("8.8.8.8")
                            .build(),
                    exposeProgress = true,
                    networkFingerprint = networkFingerprintProvider.capture(),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val bridge = buildResolverRecommendationBridge(prepared.sessionId)
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            val session = requireNotNull(stores.getScanSession(prepared.sessionId))
            val preferredPath =
                stores.getNetworkDnsPathPreference(networkFingerprintProvider.capture().scopeKey())
            val persistedReport =
                diagnosticsTestJson().decodeFromString(
                    ScanReport.serializer(),
                    requireNotNull(session.reportJson),
                )

            assertEquals("completed", session.status)
            assertTrue(requireNotNull(persistedReport.resolverRecommendation).appliedTemporarily)
            assertEquals("cloudflare", resolverOverrideStore.override.value?.resolverId)
            assertNotNull(preferredPath)
            assertEquals(1, stores.storedProbeResults(prepared.sessionId).size)
            assertEquals(2, stores.snapshotsState.value.count { it.sessionId == prepared.sessionId })
            assertEquals(2, stores.contextsState.value.count { it.sessionId == prepared.sessionId })
            assertTrue(stores.nativeEventsState.value.any { it.sessionId == prepared.sessionId })
            assertNull(timelineSource.activeScanProgress.value)
        }

    @Test
    fun `finalization applies raw path dns fallback override while service is halted and returns corrected dns path`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val resolverOverrideStore = FakeResolverOverrideStore()
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Halted to Mode.VPN)
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    resolverOverrideStore = resolverOverrideStore,
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setDnsMode(DnsModePlainUdp)
                    .setDnsProviderId(DnsProviderCustom)
                    .setDnsIp("8.8.8.8")
                    .build()
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-dns-fallback",
                    settings = settings,
                    exposeProgress = false,
                    registerActiveBridge = false,
                    kind = ScanKind.STRATEGY_PROBE,
                    profileId = "automatic-probing",
                    family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                    strategyProbeRequest = StrategyProbeRequest(suiteId = "quick_v1"),
                )
            seedPreparedScan(stores, prepared)
            val report =
                scanReportWithDnsFallbackResolverRecommendation(
                    sessionId = prepared.sessionId,
                    settings = settings,
                )

            val finalization =
                fixtures.finalizationService.finalize(
                    prepared = prepared,
                    reportJson =
                        json.encodeToString(
                            com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                                .serializer(),
                            report.toEngineScanReportWire(),
                        ),
                )
            val persisted =
                diagnosticsTestJson()
                    .decodeEngineScanReportWire(requireNotNull(stores.getScanSession(prepared.sessionId)?.reportJson))
                    .toScanReport()

            assertTrue(finalization.shouldReprobeWithCorrectedDns)
            assertEquals("cloudflare", finalization.correctedDnsPath?.resolverId)
            assertEquals("cloudflare", resolverOverrideStore.override.value?.resolverId)
            assertTrue(requireNotNull(persisted.resolverRecommendation).appliedTemporarily)
        }

    @Test
    fun `dns corrected reprobe waits for vpn auto resume before starting`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedStrategyProbeProfile(json) }
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Halted to Mode.VPN)
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setDnsMode(DnsModePlainUdp)
                    .setDnsProviderId(DnsProviderCustom)
                    .setDnsIp("8.8.8.8")
                    .build()
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-reprobe",
                    settings = settings,
                    exposeProgress = false,
                    registerActiveBridge = false,
                    kind = ScanKind.STRATEGY_PROBE,
                    profileId = "automatic-probing",
                    family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                    strategyProbeRequest = StrategyProbeRequest(suiteId = "quick_v1"),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val originalBridge = buildDnsFallbackBridge(prepared.sessionId, settings)
            fixtures.activeScanRegistry.registerBridge(
                originalBridge,
                prepared.sessionId,
                prepared.registerActiveBridge,
            )
            val handle = BridgeSessionHandle(originalBridge, prepared.sessionId, prepared.registerActiveBridge)

            backgroundScope.launch {
                delay(50)
                serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            }

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            val reprobeRequestJson = requireNotNull(fixtures.bridgeFactory.bridge.startedRequestJson)
            val reprobeRequest = json.decodeFromString(EngineScanRequestWire.serializer(), reprobeRequestJson)
            val reprobeRuntimeDns = decodeRuntimeDns(reprobeRequest)

            assertEquals("cloudflare", reprobeRuntimeDns.resolverId)
            assertTrue(
                stores.sessionsState.value.any { session ->
                    session.pathMode == ScanPathMode.IN_PATH.name && session.status == "completed"
                },
            )
        }

    @Test
    fun `dns corrected reprobe records failure when vpn service never resumes`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedStrategyProbeProfile(json) }
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Halted to Mode.VPN)
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setDnsMode(DnsModePlainUdp)
                    .setDnsProviderId(DnsProviderCustom)
                    .setDnsIp("8.8.8.8")
                    .build()
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-reprobe-timeout",
                    settings = settings,
                    exposeProgress = false,
                    registerActiveBridge = false,
                    kind = ScanKind.STRATEGY_PROBE,
                    profileId = "automatic-probing",
                    family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                    strategyProbeRequest = StrategyProbeRequest(suiteId = "quick_v1"),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val originalBridge = buildDnsFallbackBridge(prepared.sessionId, settings)
            fixtures.activeScanRegistry.registerBridge(
                originalBridge,
                prepared.sessionId,
                prepared.registerActiveBridge,
            )
            val handle = BridgeSessionHandle(originalBridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            val reprobeSession =
                stores.sessionsState.value.first { session ->
                    session.pathMode == ScanPathMode.IN_PATH.name
                }

            assertNull(fixtures.bridgeFactory.bridge.startedRequestJson)
            assertEquals("failed", reprobeSession.status)
            assertTrue(reprobeSession.summary.contains("Timed out waiting for VPN service to resume"))
        }

    private fun buildResolverRecommendationBridge(sessionId: String): FakeNetworkDiagnosticsBridge =
        FakeNetworkDiagnosticsBridge(json).apply {
            autoCompleteOnStart = false
            enqueuePassiveEvents(
                json.encodeToString(
                    ListSerializer(NativeSessionEvent.serializer()),
                    listOf(
                        NativeSessionEvent(
                            source = "native",
                            level = "warn",
                            message = "probe warn",
                            createdAt = 15L,
                        ),
                    ),
                ),
            )
            enqueueProgress(
                ScanProgress(
                    sessionId = sessionId,
                    phase = "running",
                    completedSteps = 1,
                    totalSteps = 2,
                    message = "running",
                ),
            )
            enqueueProgress(
                ScanProgress(
                    sessionId = sessionId,
                    phase = "complete",
                    completedSteps = 2,
                    totalSteps = 2,
                    message = "complete",
                    isFinished = true,
                ),
            )
            enqueueReport(scanReportWithResolverRecommendation(sessionId))
        }

    private fun buildDnsFallbackBridge(
        sessionId: String,
        settings: com.poyka.ripdpi.proto.AppSettings,
    ): FakeNetworkDiagnosticsBridge =
        FakeNetworkDiagnosticsBridge(json).apply {
            autoCompleteOnStart = false
            enqueueProgress(
                ScanProgress(
                    sessionId = sessionId,
                    phase = "complete",
                    completedSteps = 1,
                    totalSteps = 1,
                    message = "complete",
                    isFinished = true,
                ),
            )
            enqueueReport(
                scanReportWithDnsFallbackResolverRecommendation(
                    sessionId = sessionId,
                    settings = settings,
                ),
            )
        }

    @Test
    fun `hidden execution never surfaces active progress`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-hidden",
                    settings = defaultDiagnosticsAppSettings(),
                    exposeProgress = false,
                    registerActiveBridge = false,
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val bridge =
                FakeNetworkDiagnosticsBridge(json).apply {
                    autoCompleteOnStart = false
                    enqueueProgress(
                        ScanProgress(
                            sessionId = prepared.sessionId,
                            phase = "complete",
                            completedSteps = 1,
                            totalSteps = 1,
                            message = "complete",
                            isFinished = true,
                        ),
                    )
                    enqueueReport(scanReportWithResolverRecommendation(prepared.sessionId))
                }
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val progressHistory = mutableListOf<ScanProgress?>()
            val collectionJob =
                backgroundScope.launch {
                    timelineSource.activeScanProgress.collect {
                        progressHistory +=
                            it
                    }
                }

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })
            advanceUntilIdle()
            collectionJob.cancel()

            assertTrue(progressHistory.all { it == null })
        }

    @Test
    fun `missing finished report marks session failed`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(sessionId = "session-failed", settings = defaultDiagnosticsAppSettings())
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val bridge =
                FakeNetworkDiagnosticsBridge(json).apply {
                    autoCompleteOnStart = false
                    enqueueProgress(
                        ScanProgress(
                            sessionId = prepared.sessionId,
                            phase = "complete",
                            completedSteps = 1,
                            totalSteps = 1,
                            message = "complete",
                            isFinished = true,
                        ),
                    )
                }
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            val failedSession = stores.getScanSession(prepared.sessionId)
            assertEquals("failed", failedSession?.status)
            assertTrue(requireNotNull(failedSession?.summary).contains("without a report"))
            assertEquals(1, bridge.destroyCount)
            assertNull(timelineSource.activeScanProgress.value)
        }

    @Test
    fun `delayed finished report is tolerated after finished progress`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-delayed-report",
                    settings = defaultDiagnosticsAppSettings(),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val bridge =
                FakeNetworkDiagnosticsBridge(json).apply {
                    autoCompleteOnStart = false
                    enqueueProgress(
                        ScanProgress(
                            sessionId = prepared.sessionId,
                            phase = "complete",
                            completedSteps = 1,
                            totalSteps = 1,
                            message = "complete",
                            isFinished = true,
                        ),
                    )
                    repeat(8) {
                        enqueueReport(null)
                    }
                    enqueueReport(scanReportWithResolverRecommendation(prepared.sessionId))
                }
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            val session = requireNotNull(stores.getScanSession(prepared.sessionId))
            assertEquals("completed", session.status)
            assertNotNull(session.reportJson)
        }

    @Test
    fun `background automatic probing skips remembered policy when prepared fingerprint is missing`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-strategy-no-fingerprint",
                    settings = settings,
                    scanOrigin = DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
                    exposeProgress = false,
                    registerActiveBridge = false,
                    networkFingerprint = null,
                    profileId = "automatic-probing",
                    family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                    kind = ScanKind.STRATEGY_PROBE,
                    strategyProbeRequest = StrategyProbeRequest(suiteId = "quick_v1"),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val bridge =
                FakeNetworkDiagnosticsBridge(json).apply {
                    autoCompleteOnStart = false
                    enqueueProgress(
                        ScanProgress(
                            sessionId = prepared.sessionId,
                            phase = "complete",
                            completedSteps = 1,
                            totalSteps = 1,
                            message = "complete",
                            isFinished = true,
                        ),
                    )
                    enqueueReport(scanReportWithStrategyProbe(prepared.sessionId, settings))
                }
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            assertTrue(stores.rememberedPoliciesState.value.isEmpty())
        }

    private fun timelineSource(
        stores: FakeDiagnosticsHistoryStores,
        scope: CoroutineScope,
    ): DefaultDiagnosticsTimelineSource = coordinatorTimelineSource(stores, scope)
}

internal fun coordinatorTimelineSource(
    stores: FakeDiagnosticsHistoryStores,
    scope: CoroutineScope,
): DefaultDiagnosticsTimelineSource =
    DefaultDiagnosticsTimelineSource(
        profileCatalog = stores,
        scanRecordStore = stores,
        artifactReadStore = stores,
        bypassUsageHistoryStore = stores,
        mapper = DiagnosticsBoundaryMapper(diagnosticsTestJson()),
        scope = scope,
        json = diagnosticsTestJson(),
    )

internal data class ExecutionCoordinatorFixtures(
    val coordinator: DiagnosticsScanExecutionCoordinator,
    val activeScanRegistry: ActiveScanRegistry,
    val bridgeFactory: FakeNetworkDiagnosticsBridgeFactory,
    val finalizationService: ScanFinalizationService,
)

internal fun executionCoordinatorFixtures(
    stores: FakeDiagnosticsHistoryStores,
    timelineSource: DefaultDiagnosticsTimelineSource,
    serviceStateStore: FakeServiceStateStore,
    resolverOverrideStore: FakeResolverOverrideStore = FakeResolverOverrideStore(),
    networkFingerprintProvider: com.poyka.ripdpi.data.NetworkFingerprintProvider = FakeNetworkFingerprintProvider(),
    networkEdgePreferenceStore: com.poyka.ripdpi.data.diagnostics.DefaultNetworkEdgePreferenceStore =
        com.poyka.ripdpi.data.diagnostics
            .DefaultNetworkEdgePreferenceStore(stores, TestDiagnosticsHistoryClock()),
    preferredPathStore: DefaultNetworkDnsPathPreferenceStore =
        DefaultNetworkDnsPathPreferenceStore(stores, TestDiagnosticsHistoryClock()),
    rememberedNetworkPolicyStore: DefaultRememberedNetworkPolicyStore =
        DefaultRememberedNetworkPolicyStore(stores, TestDiagnosticsHistoryClock()),
    json: kotlinx.serialization.json.Json = diagnosticsTestJson(),
    bridgeFactory: FakeNetworkDiagnosticsBridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json),
): ExecutionCoordinatorFixtures {
    val activeScanRegistry = ActiveScanRegistry(timelineSource)
    val bridgeExecutionService =
        BridgeExecutionService(
            networkDiagnosticsBridgeFactory = bridgeFactory,
            activeScanRegistry = activeScanRegistry,
        )
    val passiveEventPersistenceService = PassiveEventPersistenceService(stores, json)
    val networkMetadataProvider = FakeNetworkMetadataProvider()
    val diagnosticsContextProvider = FakeDiagnosticsContextProvider()
    val scanFinalizationService =
        ScanFinalizationService(
            context = TestContext(),
            scanRecordStore = stores,
            artifactWriteStore = stores,
            networkMetadataProvider = networkMetadataProvider,
            networkFingerprintProvider = networkFingerprintProvider,
            diagnosticsContextProvider = diagnosticsContextProvider,
            serviceStateStore = serviceStateStore,
            resolverOverrideStore = resolverOverrideStore,
            rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
            networkEdgePreferenceStore = networkEdgePreferenceStore,
            networkDnsPathPreferenceStore = preferredPathStore,
            serverCapabilityStore = FakeServerCapabilityStore(),
            findingProjector = DiagnosticsFindingProjector(),
            json = json,
        )
    val appSettingsRepository = FakeAppSettingsRepository()
    val scanRequestFactory =
        DiagnosticsScanRequestFactory(
            context = TestContext(),
            networkMetadataProvider = networkMetadataProvider,
            intentResolver = DefaultDiagnosticsIntentResolver(stores, appSettingsRepository, json),
            scanContextCollector =
                DefaultScanContextCollector(
                    profileCatalog = stores,
                    networkFingerprintProvider = networkFingerprintProvider,
                    nativeNetworkSnapshotProvider =
                        object : com.poyka.ripdpi.data.NativeNetworkSnapshotProvider {
                            override fun capture() =
                                com.poyka.ripdpi.data
                                    .NativeNetworkSnapshot()
                        },
                    diagnosticsContextProvider = diagnosticsContextProvider,
                    networkDnsPathPreferenceStore = preferredPathStore,
                    networkEdgePreferenceStore = networkEdgePreferenceStore,
                    serviceStateStore = serviceStateStore,
                    json = json,
                ),
            diagnosticsPlanner = DefaultDiagnosticsPlanner(),
            engineRequestEncoder = DefaultEngineRequestEncoder(),
            json = json,
        )
    return ExecutionCoordinatorFixtures(
        coordinator =
            DiagnosticsScanExecutionCoordinator(
                scanRecordStore = stores,
                activeScanRegistry = activeScanRegistry,
                bridgeExecutionService = bridgeExecutionService,
                bridgePollingService = BridgePollingService(passiveEventPersistenceService, json),
                scanFinalizationService = scanFinalizationService,
                scanRequestFactory = scanRequestFactory,
                serviceStateStore = serviceStateStore,
            ),
        activeScanRegistry = activeScanRegistry,
        bridgeFactory = bridgeFactory,
        finalizationService = scanFinalizationService,
    )
}

@Suppress("LongMethod")
internal suspend fun preparedDiagnosticsScan(
    sessionId: String,
    settings: com.poyka.ripdpi.proto.AppSettings,
    scanOrigin: DiagnosticsScanOrigin = DiagnosticsScanOrigin.USER_INITIATED,
    exposeProgress: Boolean = true,
    registerActiveBridge: Boolean = true,
    networkFingerprint: com.poyka.ripdpi.data.NetworkFingerprint? = null,
    profileId: String = "default",
    family: DiagnosticProfileFamily = DiagnosticProfileFamily.GENERAL,
    kind: ScanKind = ScanKind.CONNECTIVITY,
    strategyProbeRequest: StrategyProbeRequest? = null,
    probePersistencePolicy: ProbePersistencePolicy? = null,
) = PreparedDiagnosticsScan(
    sessionId = sessionId,
    settings = settings,
    pathMode = ScanPathMode.RAW_PATH,
    intent =
        DiagnosticsIntent(
            profileId = profileId,
            displayName = "Diagnostics",
            settings = settings,
            kind = kind,
            family = family,
            regionTag = null,
            executionPolicy =
                ExecutionPolicy(
                    manualOnly = false,
                    allowBackground = scanOrigin == DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
                    requiresRawPath = kind == ScanKind.STRATEGY_PROBE,
                    probePersistencePolicy =
                        probePersistencePolicy
                            ?: if (kind == ScanKind.STRATEGY_PROBE &&
                                family == DiagnosticProfileFamily.AUTOMATIC_PROBING
                            ) {
                                ProbePersistencePolicy.BACKGROUND_ONLY
                            } else {
                                ProbePersistencePolicy.MANUAL_ONLY
                            },
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
            strategyProbe = strategyProbeRequest,
            requestedPathMode = ScanPathMode.RAW_PATH,
        ),
    context =
        ScanContext(
            settings = settings,
            pathMode = ScanPathMode.RAW_PATH,
            networkFingerprint = networkFingerprint,
            preferredDnsPath = null,
            networkSnapshot = null,
            serviceMode = Mode.VPN.name,
            contextSnapshot = FakeDiagnosticsContextProvider().captureContextForTest(),
            approachSnapshot =
                createStoredApproachSnapshot(
                    diagnosticsTestJson(),
                    settings,
                    null,
                    FakeDiagnosticsContextProvider().captureContextForTest(),
                ),
        ),
    plan =
        ScanPlan(
            intent =
                DiagnosticsIntent(
                    profileId = profileId,
                    displayName = "Diagnostics",
                    settings = settings,
                    kind = kind,
                    family = family,
                    regionTag = null,
                    executionPolicy =
                        ExecutionPolicy(
                            manualOnly = false,
                            allowBackground = scanOrigin == DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
                            requiresRawPath = kind == ScanKind.STRATEGY_PROBE,
                            probePersistencePolicy =
                                probePersistencePolicy
                                    ?: if (kind == ScanKind.STRATEGY_PROBE &&
                                        family == DiagnosticProfileFamily.AUTOMATIC_PROBING
                                    ) {
                                        ProbePersistencePolicy.BACKGROUND_ONLY
                                    } else {
                                        ProbePersistencePolicy.MANUAL_ONLY
                                    },
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
                    strategyProbe = strategyProbeRequest,
                    requestedPathMode = ScanPathMode.RAW_PATH,
                ),
            context =
                ScanContext(
                    settings = settings,
                    pathMode = ScanPathMode.RAW_PATH,
                    networkFingerprint = networkFingerprint,
                    preferredDnsPath = null,
                    networkSnapshot = null,
                    serviceMode = Mode.VPN.name,
                    contextSnapshot = FakeDiagnosticsContextProvider().captureContextForTest(),
                    approachSnapshot =
                        createStoredApproachSnapshot(
                            diagnosticsTestJson(),
                            settings,
                            null,
                            FakeDiagnosticsContextProvider().captureContextForTest(),
                        ),
                ),
            proxyHost = null,
            proxyPort = null,
            dnsTargets = emptyList(),
            probeTasks = emptyList(),
        ),
    requestJson = "{}",
    scanOrigin = scanOrigin,
    launchTrigger = null,
    exposeProgress = exposeProgress,
    registerActiveBridge = registerActiveBridge,
    networkFingerprint = networkFingerprint,
    preferredDnsPath = null,
    initialSession =
        diagnosticsSession(
            id = sessionId,
            profileId = profileId,
            pathMode = ScanPathMode.RAW_PATH.name,
            summary = "running",
            status = "running",
            reportJson = null,
        ),
    preScanSnapshot =
        com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            snapshotKind = "pre_scan",
            payloadJson =
                diagnosticsTestJson().encodeToString(
                    NetworkSnapshotModel.serializer(),
                    networkSnapshotModelForTest(),
                ),
            capturedAt = 10L,
        ),
    preScanContext =
        com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            contextKind = "pre_scan",
            payloadJson =
                diagnosticsTestJson().encodeToString(
                    DiagnosticContextModel.serializer(),
                    FakeDiagnosticsContextProvider().captureContextForTest(),
                ),
            capturedAt = 10L,
        ),
)

internal suspend fun seedPreparedScan(
    stores: FakeDiagnosticsHistoryStores,
    prepared: PreparedDiagnosticsScan,
) {
    stores.upsertScanSession(prepared.initialSession)
    stores.upsertSnapshot(prepared.preScanSnapshot)
    stores.upsertContextSnapshot(prepared.preScanContext)
}

internal fun scanReportWithResolverRecommendation(sessionId: String) =
    ScanReport(
        sessionId = sessionId,
        profileId = "default",
        pathMode = ScanPathMode.RAW_PATH,
        startedAt = 10L,
        finishedAt = 20L,
        summary = "resolver recommendation",
        results =
            listOf(
                ProbeResult(
                    probeType = "dns_integrity",
                    target = "blocked.example",
                    outcome = "dns_substitution",
                    details =
                        listOf(
                            ProbeDetail("encryptedResolverId", com.poyka.ripdpi.data.DnsProviderCloudflare),
                            ProbeDetail("encryptedProtocol", com.poyka.ripdpi.data.EncryptedDnsProtocolDoh),
                            ProbeDetail("encryptedAddresses", "1.1.1.1"),
                            ProbeDetail("encryptedBootstrapValidated", "true"),
                            ProbeDetail("encryptedLatencyMs", "32"),
                        ),
                ),
            ),
    )

internal fun scanReportWithDnsFallbackResolverRecommendation(
    sessionId: String,
    settings: com.poyka.ripdpi.proto.AppSettings,
): ScanReport =
    scanReportWithStrategyProbe(
        sessionId = sessionId,
        settings = settings,
        completionKind = StrategyProbeCompletionKind.DNS_TAMPERING_WITH_FALLBACK,
        resolverRecommendation = resolverRecommendationForCoordinator(),
    ).copy(
        results = scanReportWithResolverRecommendation(sessionId).results,
    )

@Suppress("UnusedParameter")
internal fun scanReportWithStrategyProbe(
    sessionId: String,
    settings: com.poyka.ripdpi.proto.AppSettings,
    profileId: String = "automatic-probing",
    suiteId: String = "quick_v1",
    auditAssessment: StrategyProbeAuditAssessment? = strategyProbeAuditAssessmentForCoordinator(),
    completionKind: StrategyProbeCompletionKind = StrategyProbeCompletionKind.NORMAL,
    tcpSucceededTargets: Int = 1,
    quicSucceededTargets: Int = 1,
    resolverRecommendation: ResolverRecommendation? = null,
): ScanReport =
    ScanReport(
        sessionId = sessionId,
        profileId = profileId,
        pathMode = ScanPathMode.RAW_PATH,
        startedAt = 10L,
        finishedAt = 20L,
        summary = "strategy probe",
        resolverRecommendation = resolverRecommendation,
        results =
            listOf(
                ProbeResult(
                    probeType = "http",
                    target = "example.org",
                    outcome = "success",
                ),
            ),
        strategyProbeReport =
            StrategyProbeReport(
                suiteId = suiteId,
                tcpCandidates =
                    listOf(
                        StrategyProbeCandidateSummary(
                            id = "tcp-1",
                            label = "TCP candidate",
                            family = "hostfake",
                            outcome = "success",
                            rationale = "best",
                            succeededTargets = tcpSucceededTargets,
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
                            family = "quic_realistic_burst",
                            outcome = "success",
                            rationale = "best",
                            succeededTargets = quicSucceededTargets,
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
                        recommendedProxyConfigJson = validRecommendedProxyConfigJsonForCoordinator(),
                    ),
                completionKind = completionKind,
                auditAssessment = auditAssessment,
            ),
    )

internal fun strategyProbeAuditAssessmentForCoordinator(
    confidenceLevel: StrategyProbeAuditConfidenceLevel = StrategyProbeAuditConfidenceLevel.HIGH,
    matrixCoveragePercent: Int = 100,
    winnerCoveragePercent: Int = 100,
): StrategyProbeAuditAssessment =
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
                matrixCoveragePercent = matrixCoveragePercent,
                winnerCoveragePercent = winnerCoveragePercent,
            ),
        confidence =
            StrategyProbeAuditConfidence(
                level = confidenceLevel,
                score = 100,
                rationale = "Matrix coverage and winner strength are consistent",
            ),
    )

internal fun validRecommendedProxyConfigJsonForCoordinator(): String =
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

internal fun resolverRecommendationForCoordinator(): ResolverRecommendation =
    ResolverRecommendation(
        triggerOutcome = "dns_substitution",
        selectedResolverId = "cloudflare",
        selectedProtocol = "doh",
        selectedEndpoint = "https://cloudflare-dns.com/dns-query",
        rationale = "DNS tampering detected",
    )

internal fun decodeRuntimeDns(request: EngineScanRequestWire) =
    requireNotNull(
        requireNotNull(
            decodeRipDpiProxyUiPreferences(requireNotNull(request.strategyProbe?.baseProxyConfigJson)),
        ).runtimeContext?.encryptedDns,
    )

internal fun strategyProbeFingerprint(
    ssid: String,
    gateway: String,
) = com.poyka.ripdpi.data.NetworkFingerprint(
    transport = "wifi",
    networkValidated = true,
    captivePortalDetected = false,
    privateDnsMode = "system",
    dnsServers = listOf("1.1.1.1"),
    wifi =
        com.poyka.ripdpi.data.WifiNetworkIdentityTuple(
            ssid = ssid,
            bssid = "aa:bb:cc:dd:ee:ff",
            gateway = gateway,
        ),
)
