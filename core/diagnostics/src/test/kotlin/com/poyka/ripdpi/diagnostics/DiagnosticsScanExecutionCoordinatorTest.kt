package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.toRipDpiRuntimeContext
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ExecutionPolicy
import com.poyka.ripdpi.diagnostics.domain.ScanContext
import com.poyka.ripdpi.diagnostics.domain.ScanPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
            val bridge =
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
                            sessionId = prepared.sessionId,
                            phase = "running",
                            completedSteps = 1,
                            totalSteps = 2,
                            message = "running",
                        ),
                    )
                    enqueueProgress(
                        ScanProgress(
                            sessionId = prepared.sessionId,
                            phase = "complete",
                            completedSteps = 2,
                            totalSteps = 2,
                            message = "complete",
                            isFinished = true,
                        ),
                    )
                    enqueueReport(scanReportWithResolverRecommendation(prepared.sessionId))
                }
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
    fun `strategy probe completion remembers validated network policy`() =
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
            val preparedFingerprint = strategyProbeFingerprint(ssid = "network-default", gateway = "192.0.2.1")
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    networkFingerprintProvider = MutableNetworkFingerprintProvider(preparedFingerprint),
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-strategy",
                    settings = settings,
                    networkFingerprint = preparedFingerprint,
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

            assertFalse(stores.rememberedPoliciesState.value.isEmpty())
            assertEquals(
                "validated",
                stores.rememberedPoliciesState.value
                    .single()
                    .status,
            )
            assertEquals(
                preparedFingerprint.scopeKey(),
                stores.rememberedPoliciesState.value
                    .single()
                    .fingerprintHash,
            )
        }

    @Test
    fun `strategy probe completion keeps prepared fingerprint when provider changes`() =
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
            val preparedFingerprint = strategyProbeFingerprint(ssid = "network-a", gateway = "192.0.2.10")
            val changedFingerprint = strategyProbeFingerprint(ssid = "network-b", gateway = "192.0.2.20")
            val networkFingerprintProvider = MutableNetworkFingerprintProvider(preparedFingerprint)
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    networkFingerprintProvider = networkFingerprintProvider,
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-strategy-fingerprint",
                    settings = settings,
                    networkFingerprint = preparedFingerprint,
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
            networkFingerprintProvider.fingerprint = changedFingerprint

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            val rememberedPolicy = stores.rememberedPoliciesState.value.single()
            assertEquals(preparedFingerprint.scopeKey(), rememberedPolicy.fingerprintHash)
            assertNotEquals(changedFingerprint.scopeKey(), rememberedPolicy.fingerprintHash)
        }

    @Test
    fun `strategy probe completion skips remembered policy when prepared fingerprint is missing`() =
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
                    networkFingerprint = null,
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
    ): DefaultDiagnosticsTimelineSource =
        DefaultDiagnosticsTimelineSource(
            profileCatalog = stores,
            scanRecordStore = stores,
            artifactReadStore = stores,
            bypassUsageHistoryStore = stores,
            mapper = DiagnosticsBoundaryMapper(json),
            scope = scope,
            json = json,
        )
}

private data class ExecutionCoordinatorFixtures(
    val coordinator: DiagnosticsScanExecutionCoordinator,
    val activeScanRegistry: ActiveScanRegistry,
)

private fun executionCoordinatorFixtures(
    stores: FakeDiagnosticsHistoryStores,
    timelineSource: DefaultDiagnosticsTimelineSource,
    serviceStateStore: FakeServiceStateStore,
    resolverOverrideStore: FakeResolverOverrideStore = FakeResolverOverrideStore(),
    networkFingerprintProvider: com.poyka.ripdpi.data.NetworkFingerprintProvider = FakeNetworkFingerprintProvider(),
    preferredPathStore: DefaultNetworkDnsPathPreferenceStore =
        DefaultNetworkDnsPathPreferenceStore(stores, TestDiagnosticsHistoryClock()),
    rememberedNetworkPolicyStore: DefaultRememberedNetworkPolicyStore =
        DefaultRememberedNetworkPolicyStore(stores, TestDiagnosticsHistoryClock()),
    json: kotlinx.serialization.json.Json = diagnosticsTestJson(),
): ExecutionCoordinatorFixtures {
    val activeScanRegistry = ActiveScanRegistry(timelineSource)
    val bridgeExecutionService =
        BridgeExecutionService(
            networkDiagnosticsBridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json),
            activeScanRegistry = activeScanRegistry,
        )
    val passiveEventPersistenceService = PassiveEventPersistenceService(stores, json)
    val scanFinalizationService =
        ScanFinalizationService(
            context = TestContext(),
            scanRecordStore = stores,
            artifactWriteStore = stores,
            networkMetadataProvider = FakeNetworkMetadataProvider(),
            networkFingerprintProvider = networkFingerprintProvider,
            diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
            serviceStateStore = serviceStateStore,
            resolverOverrideStore = resolverOverrideStore,
            rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
            networkDnsPathPreferenceStore = preferredPathStore,
            findingProjector = DiagnosticsFindingProjector(),
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
            ),
        activeScanRegistry = activeScanRegistry,
    )
}

@Suppress("LongMethod")
private suspend fun preparedDiagnosticsScan(
    sessionId: String,
    settings: com.poyka.ripdpi.proto.AppSettings,
    exposeProgress: Boolean = true,
    registerActiveBridge: Boolean = true,
    networkFingerprint: com.poyka.ripdpi.data.NetworkFingerprint? = null,
) = PreparedDiagnosticsScan(
    sessionId = sessionId,
    settings = settings,
    pathMode = ScanPathMode.RAW_PATH,
    intent =
        DiagnosticsIntent(
            profileId = "default",
            displayName = "Diagnostics",
            settings = settings,
            kind = ScanKind.CONNECTIVITY,
            family = DiagnosticProfileFamily.GENERAL,
            regionTag = null,
            executionPolicy = ExecutionPolicy(manualOnly = false, allowBackground = false, requiresRawPath = false),
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
            strategyProbe = null,
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
                    profileId = "default",
                    displayName = "Diagnostics",
                    settings = settings,
                    kind = ScanKind.CONNECTIVITY,
                    family = DiagnosticProfileFamily.GENERAL,
                    regionTag = null,
                    executionPolicy =
                        ExecutionPolicy(manualOnly = false, allowBackground = false, requiresRawPath = false),
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
                    strategyProbe = null,
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
    exposeProgress = exposeProgress,
    registerActiveBridge = registerActiveBridge,
    networkFingerprint = networkFingerprint,
    preferredDnsPath = null,
    initialSession =
        diagnosticsSession(
            id = sessionId,
            profileId = "default",
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

private suspend fun seedPreparedScan(
    stores: FakeDiagnosticsHistoryStores,
    prepared: PreparedDiagnosticsScan,
) {
    stores.upsertScanSession(prepared.initialSession)
    stores.upsertSnapshot(prepared.preScanSnapshot)
    stores.upsertContextSnapshot(prepared.preScanContext)
}

private fun scanReportWithResolverRecommendation(sessionId: String) =
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

private fun scanReportWithStrategyProbe(
    sessionId: String,
    settings: com.poyka.ripdpi.proto.AppSettings,
): ScanReport {
    val proxyConfigJson =
        RipDpiProxyUIPreferences
            .fromSettings(
                settings,
                null,
                null,
                settings.activeDnsSettings().toRipDpiRuntimeContext(),
            ).toNativeConfigJson()
    return ScanReport(
        sessionId = sessionId,
        profileId = "automatic-probing",
        pathMode = ScanPathMode.RAW_PATH,
        startedAt = 10L,
        finishedAt = 20L,
        summary = "strategy probe",
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
                suiteId = "quick_v1",
                tcpCandidates =
                    listOf(
                        StrategyProbeCandidateSummary(
                            id = "tcp-1",
                            label = "TCP candidate",
                            family = "split",
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
                            family = "quic_burst",
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
            ),
    )
}

private fun strategyProbeFingerprint(
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
