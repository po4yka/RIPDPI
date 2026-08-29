package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiChainConfig
import com.poyka.ripdpi.core.RipDpiProtocolConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiQuicConfig
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.DiagnosticsProxyCredentials
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RawPathExecutionCancelledException
import com.poyka.ripdpi.data.RawPathExecutionOutcome
import com.poyka.ripdpi.data.RawPathExecutionResult
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.RawPathSettlementDurableStatePrefix
import com.poyka.ripdpi.diagnostics.contract.engine.EngineInPathRouteWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProxyCredentialsWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ExecutionPolicy
import com.poyka.ripdpi.diagnostics.domain.ScanContext
import com.poyka.ripdpi.diagnostics.domain.ScanPlan
import com.poyka.ripdpi.diagnostics.finalization.RawPathSettlementBarrier
import com.poyka.ripdpi.diagnostics.finalization.RawPathSettlementContextKind
import kotlinx.coroutines.CancellationException
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
class DiagnosticsScanPolicyFinalizationTest {
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

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)

            val session = requireNotNull(stores.getScanSession(prepared.sessionId))
            val preferredPath =
                stores.getNetworkDnsPathPreference(networkFingerprintProvider.capture().scopeKey())
            val persistedReport =
                diagnosticsTestJson()
                    .decodeEngineScanReportWire(requireNotNull(session.reportJson))
                    .toScanReport()

            assertEquals("completed", session.status)
            assertTrue(requireNotNull(persistedReport.resolverRecommendation).appliedTemporarily)
            assertEquals("cloudflare", resolverOverrideStore.override.value?.resolverId)
            assertNotNull(preferredPath)
            assertEquals(1, stores.storedProbeResults(prepared.sessionId).size)
            assertEquals(2, stores.snapshotsState.value.count { it.sessionId == prepared.sessionId })
            assertEquals(3, stores.contextsState.value.count { it.sessionId == prepared.sessionId })
            assertTrue(stores.nativeEventsState.value.any { it.sessionId == prepared.sessionId })
            assertNull(timelineSource.activeScanProgress.value)
        }

    @Test
    fun `post persistence failure keeps report but fails only after settlement receipt`() =
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
                    sessionId = "session-post-persist-failure",
                    settings = defaultDiagnosticsAppSettings(),
                )
            seedPreparedScan(stores, prepared)
            stores.beforeUpsertSnapshot = { error("injected post-persistence failure") }
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val bridge = buildResolverRecommendationBridge(prepared.sessionId)
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)

            val session = requireNotNull(stores.getScanSession(prepared.sessionId))
            assertEquals("failed", session.status)
            assertEquals("injected post-persistence failure", session.summary)
            assertNotNull(session.reportJson)
            assertEquals(1, stores.storedProbeResults(prepared.sessionId).size)
            assertTrue(
                stores.contextsState.value.any { context ->
                    context.sessionId == prepared.sessionId &&
                        context.contextKind == RawPathSettlementContextKind
                },
            )
        }

    @Test
    fun `raw path settlement retries the whole atomic publication after terminal write fault`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = timelineSource(stores, backgroundScope)
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-settlement-retry",
                    settings = defaultDiagnosticsAppSettings(),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val bridge = buildResolverRecommendationBridge(prepared.sessionId)
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)
            var terminalWriteAttempts = 0
            var receiptVisibleWithTerminal = false
            stores.beforeRawPathSettlementTerminalWrite = {
                terminalWriteAttempts += 1
                if (terminalWriteAttempts == 1) error("one-shot terminal write fault")
            }
            stores.afterUpsertScanSession = { session ->
                if (session.id == prepared.sessionId && session.status == "completed") {
                    receiptVisibleWithTerminal =
                        stores.contextsState.value.any { context ->
                            context.sessionId == prepared.sessionId &&
                                context.contextKind == RawPathSettlementContextKind
                        }
                }
            }

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)

            assertEquals(2, stores.rawPathSettlementCommitCount.get())
            assertTrue(receiptVisibleWithTerminal)
            assertEquals("completed", stores.getScanSession(prepared.sessionId)?.status)
            assertEquals(
                1,
                stores.contextsState.value.count { context ->
                    context.sessionId == prepared.sessionId && context.contextKind == RawPathSettlementContextKind
                },
            )
            assertTrue(
                stores.terminalOutboxState.value.none { marker ->
                    marker.key.startsWith(RawPathSettlementDurableStatePrefix)
                },
            )
        }

    @Test
    fun `permanent atomic publication fault leaves recoverable marker without partial receipt`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = timelineSource(stores, backgroundScope)
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-settlement-recovery",
                    settings = defaultDiagnosticsAppSettings(),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val bridge = buildResolverRecommendationBridge(prepared.sessionId)
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)
            stores.beforeRawPathSettlementTerminalWrite = { error("persistent terminal write fault") }

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)

            assertEquals(2, stores.rawPathSettlementCommitCount.get())
            assertEquals("running", stores.getScanSession(prepared.sessionId)?.status)
            assertTrue(
                stores.contextsState.value.none { context ->
                    context.sessionId == prepared.sessionId && context.contextKind == RawPathSettlementContextKind
                },
            )
            assertEquals(
                1,
                stores.terminalOutboxState.value.count { marker ->
                    marker.key.startsWith(RawPathSettlementDurableStatePrefix)
                },
            )

            stores.beforeRawPathSettlementTerminalWrite = {}
            fixtures.rawPathSettlementBarrier.recoverPending()

            assertEquals("completed", stores.getScanSession(prepared.sessionId)?.status)
            assertEquals(
                1,
                stores.contextsState.value.count { context ->
                    context.sessionId == prepared.sessionId && context.contextKind == RawPathSettlementContextKind
                },
            )
            assertTrue(
                stores.terminalOutboxState.value.none { marker ->
                    marker.key.startsWith(RawPathSettlementDurableStatePrefix)
                },
            )
        }

    @Test
    fun `raw path failure after report persistence publishes failed terminal after receipt`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = timelineSource(stores, backgroundScope)
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-post-report-block-failure",
                    settings = defaultDiagnosticsAppSettings(),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val bridge = buildResolverRecommendationBridge(prepared.sessionId)
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)
            var receiptExistedBeforeTerminal = false
            stores.afterUpsertScanSession = { session ->
                if (session.id == prepared.sessionId && session.status == "failed") {
                    receiptExistedBeforeTerminal =
                        stores.contextsState.value.any { context ->
                            context.sessionId == prepared.sessionId &&
                                context.contextKind == RawPathSettlementContextKind
                        }
                }
            }

            fixtures.coordinator.execute(
                prepared = prepared,
                handle = handle,
                rawPathRunner = { block ->
                    block()
                    completedRawPathExecutionResult(
                        executionOutcome = RawPathExecutionOutcome.BlockFailed,
                        executionFailure = "passive event persistence failed",
                    )
                },
            )

            val session = requireNotNull(stores.getScanSession(prepared.sessionId))
            assertTrue(receiptExistedBeforeTerminal)
            assertEquals("failed", session.status)
            assertEquals("passive event persistence failed", session.summary)
            assertNotNull(session.reportJson)
        }

    @Test
    fun `in-path route generation change before report does not discard scan results`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = timelineSource(stores, backgroundScope)
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    json = json,
                )
            val initialLease =
                DiagnosticsInPathRouteLease(
                    runtimeId = "vpn-runtime",
                    routeGeneration = 1,
                    issuedRevision = 1L,
                    host = "127.0.0.1",
                    port = 19080,
                    credentials = DiagnosticsProxyCredentials("scan-user", "scan-secret"),
                )
            fixtures.runtimeCoordinator.updateInPathRouteLease(initialLease)
            val base =
                preparedDiagnosticsScan(
                    sessionId = "session-route-generation-change",
                    settings = defaultDiagnosticsAppSettings(),
                )
            val request =
                EngineScanRequestWire(
                    profileId = base.intent.profileId,
                    displayName = base.intent.displayName,
                    pathMode = ScanPathMode.RAW_PATH,
                )
            val prepared =
                base.copy(
                    pathMode = ScanPathMode.IN_PATH,
                    context = base.context.copy(pathMode = ScanPathMode.IN_PATH),
                    requestJson =
                        json.encodeToString(
                            EngineScanRequestWire.serializer(),
                            request.copy(
                                pathMode = ScanPathMode.IN_PATH,
                                inPathRoute =
                                    EngineInPathRouteWire(
                                        host = initialLease.host,
                                        port = initialLease.port,
                                        credentials =
                                            EngineProxyCredentialsWire(
                                                username = initialLease.credentials.username,
                                                password = initialLease.credentials.password,
                                            ),
                                    ),
                            ),
                        ),
                    initialSession = base.initialSession.copy(pathMode = ScanPathMode.IN_PATH.name),
                    inPathRouteLease = initialLease,
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val bridge = fixtures.bridgeFactory.bridge
            bridge.afterStartScan = {
                fixtures.runtimeCoordinator.updateInPathRouteLease(
                    initialLease.copy(routeGeneration = 2, credentials = DiagnosticsProxyCredentials("new", "secret")),
                )
            }
            bridge.startScan(prepared.requestJson, prepared.sessionId)
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(
                prepared,
                handle,
                rawPathRunner = { block -> runSettledRawPathBlock(block) },
            )

            val session = requireNotNull(stores.getScanSession(prepared.sessionId))
            assertEquals("completed", session.status)
            assertNotNull(session.reportJson)
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
}

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsScanDnsCorrectedReprobeTest {
    private val json = diagnosticsTestJson()

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
                fixtures.runtimeCoordinator.updateInPathRouteLease(
                    DiagnosticsInPathRouteLease(
                        runtimeId = "vpn-runtime",
                        routeGeneration = 7,
                        issuedRevision = 1L,
                        host = "127.0.0.1",
                        port = 19080,
                        credentials = DiagnosticsProxyCredentials("reprobe-user", "reprobe-secret"),
                    ),
                )
                serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            }

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)

            val reprobeRequestJson = requireNotNull(fixtures.bridgeFactory.bridge.startedRequestJson)
            val reprobeRequest = json.decodeFromString(EngineScanRequestWire.serializer(), reprobeRequestJson)
            val reprobeRuntimeDns = decodeRuntimeDns(reprobeRequest)

            assertEquals("cloudflare", reprobeRuntimeDns.resolverId)
            assertEquals("127.0.0.1", reprobeRequest.inPathRoute?.host)
            assertEquals(19080, reprobeRequest.inPathRoute?.port)
            assertEquals("reprobe-user", reprobeRequest.inPathRoute?.credentials?.username)
            assertEquals("reprobe-secret", reprobeRequest.inPathRoute?.credentials?.password)
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

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)

            val reprobeSession =
                stores.sessionsState.value.first { session ->
                    session.pathMode == ScanPathMode.IN_PATH.name
                }

            assertNull(fixtures.bridgeFactory.bridge.startedRequestJson)
            assertEquals("failed", reprobeSession.status)
            assertTrue(reprobeSession.summary.contains("Timed out waiting for VPN service to resume"))
        }
}

private fun buildResolverRecommendationBridge(sessionId: String): FakeNetworkDiagnosticsBridge {
    val json = diagnosticsTestJson()
    return FakeNetworkDiagnosticsBridge(json).apply {
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
}

internal fun buildDnsFallbackBridge(
    sessionId: String,
    settings: com.poyka.ripdpi.proto.AppSettings,
): FakeNetworkDiagnosticsBridge {
    val json = diagnosticsTestJson()
    return FakeNetworkDiagnosticsBridge(json).apply {
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
}

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsScanExecutionLifecycleTest {
    private val json = diagnosticsTestJson()

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

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)
            advanceUntilIdle()
            collectionJob.cancel()

            assertTrue(progressHistory.all { it == null })
        }

    @Test
    fun `external cancellation destroys bridge before it is rethrown`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = timelineSource(stores, backgroundScope)
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-owner-cancelled",
                    settings = defaultDiagnosticsAppSettings(),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val bridge = FakeNetworkDiagnosticsBridge(json)
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)
            var thrown: CancellationException? = null

            try {
                fixtures.coordinator.execute(
                    prepared = prepared,
                    handle = handle,
                    rawPathRunner = {
                        val cancellation = CancellationException("owner stopped")
                        throw RawPathExecutionCancelledException(
                            result =
                                completedRawPathExecutionResult(
                                    executionOutcome = RawPathExecutionOutcome.BlockCancelled,
                                ),
                            cause = cancellation,
                        )
                    },
                )
            } catch (error: CancellationException) {
                thrown = error
            }

            assertEquals("owner stopped", thrown?.message)
            assertEquals(1, bridge.destroyCount)
            assertTrue(!fixtures.activeScanRegistry.hasVisibleActiveScan())
            assertEquals("failed", stores.getScanSession(prepared.sessionId)?.status)
            val settlementContext =
                stores.contextsState.value.single { context ->
                    context.sessionId == prepared.sessionId &&
                        context.contextKind == RawPathSettlementContextKind
                }
            assertEquals(
                RawPathExecutionOutcome.BlockCancelled,
                json
                    .decodeFromString(RawPathExecutionResult.serializer(), settlementContext.payloadJson)
                    .executionOutcome,
            )
        }

    @Test
    fun `raw path entry failure persists receipt before failed terminal session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = timelineSource(stores, backgroundScope)
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Halted to Mode.VPN),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-entry-failed",
                    settings = defaultDiagnosticsAppSettings(),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val bridge = FakeNetworkDiagnosticsBridge(json)
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)
            var receiptExistedBeforeTerminal = false
            stores.afterUpsertScanSession = { session ->
                if (session.id == prepared.sessionId && session.status == "failed") {
                    receiptExistedBeforeTerminal =
                        stores.contextsState.value.any { context ->
                            context.sessionId == prepared.sessionId &&
                                context.contextKind == RawPathSettlementContextKind
                        }
                }
            }

            fixtures.coordinator.execute(
                prepared = prepared,
                handle = handle,
                rawPathRunner = {
                    completedRawPathExecutionResult(
                        executionOutcome = RawPathExecutionOutcome.EntryFailed,
                        executionFailure = "runtime stop failed",
                    )
                },
            )

            assertTrue(receiptExistedBeforeTerminal)
            assertEquals("failed", stores.getScanSession(prepared.sessionId)?.status)
            assertEquals("runtime stop failed", stores.getScanSession(prepared.sessionId)?.summary)
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

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)

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

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)

            val session = requireNotNull(stores.getScanSession(prepared.sessionId))
            assertEquals("completed", session.status)
            assertNotNull(session.reportJson)
        }

    @Test
    fun `available report finalizes scan when final progress is missed`() =
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
                    sessionId = "session-report-before-progress",
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
                            phase = "tcp",
                            completedSteps = 52,
                            totalSteps = 57,
                            message = "TCP Quad9",
                            isFinished = false,
                        ),
                    )
                    enqueueReport(scanReportWithResolverRecommendation(prepared.sessionId))
                }
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)

            val session = requireNotNull(stores.getScanSession(prepared.sessionId))
            assertEquals("completed", session.status)
            assertNotNull(session.reportJson)
            assertEquals(1, stores.storedProbeResults(prepared.sessionId).size)
            assertNull(timelineSource.activeScanProgress.value)
        }

    @Test
    fun `polling timeout marks session failed and clears active progress`() =
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
                    sessionId = "session-poll-timeout",
                    settings = defaultDiagnosticsAppSettings(),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val bridge =
                FakeNetworkDiagnosticsBridge(json).apply {
                    autoCompleteOnStart = false
                }
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)

            val session = requireNotNull(stores.getScanSession(prepared.sessionId))
            assertEquals("failed", session.status)
            assertTrue(session.summary.contains("timed out", ignoreCase = true))
            assertEquals(0, stores.storedProbeResults(prepared.sessionId).size)
            assertNull(timelineSource.activeScanProgress.value)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsScanRememberedPolicyTest {
    private val json = diagnosticsTestJson()

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

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)

            assertTrue(stores.rememberedPoliciesState.value.isEmpty())
        }
}

private fun timelineSource(
    stores: FakeDiagnosticsHistoryStores,
    scope: CoroutineScope,
): DefaultDiagnosticsTimelineSource = coordinatorTimelineSource(stores, scope)

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
    val scanController: DefaultDiagnosticsScanController,
    val activeScanRegistry: ActiveScanRegistry,
    val bridgeFactory: FakeNetworkDiagnosticsBridgeFactory,
    val finalizationService: ScanFinalizationService,
    val runtimeCoordinator: FakeDiagnosticsRuntimeCoordinator,
    val rawPathSettlementBarrier: RawPathSettlementBarrier,
)

class DiagnosticsInPathRouteAuthorityTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `owned route through terminal response grants active observation authority`() =
        runTest { verifyAuthority(RouteChange.None, StrategyActivePathAuthority.OWNED_ROUTE_LEASE_AT_SCAN) }

    @Test
    fun `route change before terminal response leaves active observation unverified`() =
        runTest { verifyAuthority(RouteChange.BeforeTerminal, StrategyActivePathAuthority.UNVERIFIED) }

    @Test
    fun `route change during persistence preserves measured historical authority`() =
        runTest {
            verifyAuthority(
                RouteChange.DuringPersistence,
                StrategyActivePathAuthority.OWNED_ROUTE_LEASE_AT_SCAN,
            )
        }

    @Test
    fun `observed route loss cannot be healed before terminal receipt`() {
        var current = true
        val state = BridgeReportPollingState { current }
        current = false
        state.observeRoute()
        current = true
        val (_, report) = ownedActiveObservationFixture("session-sticky-route-loss")
        state.observe(json.encodeToString(report.toEngineScanReportWire()))
        assertEquals(false, state.ownedInPathRouteAtCompletion)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.verifyAuthority(
        change: RouteChange,
        expected: StrategyActivePathAuthority,
    ) {
        val stores = FakeDiagnosticsHistoryStores()
        val fixtures =
            executionCoordinatorFixtures(
                stores = stores,
                timelineSource = timelineSource(stores, backgroundScope),
                serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
            )
        val (prepared, report) = ownedActiveObservationFixture("session-route-authority-$change")
        val lease = requireNotNull(prepared.inPathRouteLease)
        fixtures.runtimeCoordinator.updateInPathRouteLease(lease)
        seedPreparedScan(stores, prepared)
        fixtures.activeScanRegistry.rememberPreparedScan(prepared)
        val bridge = fixtures.bridgeFactory.bridge
        bridge.autoCompleteOnStart = false
        bridge.enqueueReport(report)
        bridge.startScan(prepared.requestJson, prepared.sessionId)
        fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
        if (change == RouteChange.BeforeTerminal) {
            fixtures.runtimeCoordinator.updateInPathRouteLease(lease.copy(routeGeneration = lease.routeGeneration + 1))
        }
        if (change == RouteChange.DuringPersistence) {
            stores.beforeUpsertSnapshot = {
                fixtures.runtimeCoordinator.updateInPathRouteLease(null)
            }
        }
        fixtures.coordinator.execute(
            prepared,
            BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge),
            rawPathRunner = { block -> runSettledRawPathBlock(block) },
        )
        val session = requireNotNull(stores.getScanSession(prepared.sessionId))
        assertEquals("completed", session.status)
        val persisted = json.decodeEngineScanReportWire(requireNotNull(session.reportJson))
        assertEquals(expected, persisted.strategyProbeReport?.activePathObservation?.activePathAuthority)
        assertEquals(change == RouteChange.None, fixtures.runtimeCoordinator.isInPathRouteLeaseCurrent(lease))
    }

    private enum class RouteChange { None, BeforeTerminal, DuringPersistence }
}

@Suppress("LongMethod")
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
    controllerScope: CoroutineScope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
): ExecutionCoordinatorFixtures {
    val activeScanRegistry = ActiveScanRegistry(timelineSource)
    val bridgeExecutionService =
        BridgeExecutionService(
            networkDiagnosticsBridgeFactory = bridgeFactory,
            activeScanRegistry = activeScanRegistry,
            retirementQueue = testBridgeRetirementQueue(controllerScope),
        )
    val passiveEventPersistenceService = PassiveEventPersistenceService(stores, json)
    val networkMetadataProvider = FakeNetworkMetadataProvider()
    val diagnosticsContextProvider = FakeDiagnosticsContextProvider()
    val rawPathSettlementBarrier = RawPathSettlementBarrier(stores, stores.rawPathSettlementStore, json)
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
            rawPathSettlementBarrier = rawPathSettlementBarrier,
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
            activeProbeSafetyPolicy = ActiveProbeSafetyPolicy(),
            json = json,
        )
    val runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator()
    val coordinator =
        DiagnosticsScanExecutionCoordinator(
            scanRecordStore = stores,
            activeScanRegistry = activeScanRegistry,
            bridgeExecutionService = bridgeExecutionService,
            bridgePollingService = BridgePollingService(passiveEventPersistenceService, json),
            scanFinalizationService = scanFinalizationService,
            scanRequestFactory = scanRequestFactory,
            serviceStateStore = serviceStateStore,
            runtimeCoordinator = runtimeCoordinator,
            json = json,
        )
    val scanController =
        DefaultDiagnosticsScanController(
            appSettingsRepository = appSettingsRepository,
            scanRecordStore = stores,
            artifactWriteStore = stores,
            runtimeCoordinator = runtimeCoordinator,
            serviceStateStore = serviceStateStore,
            scanRequestFactory = scanRequestFactory,
            scanAdmissionService = ScanAdmissionService(appSettingsRepository, stores, activeScanRegistry, json),
            activeScanRegistry = activeScanRegistry,
            bridgeExecutionService = bridgeExecutionService,
            executionCoordinator = coordinator,
            hiddenProbeConflictRequestFactory = HiddenProbeConflictRequestFactory(json),
            scope = controllerScope,
            json = json,
        )
    return ExecutionCoordinatorFixtures(
        coordinator = coordinator,
        scanController = scanController,
        activeScanRegistry = activeScanRegistry,
        bridgeFactory = bridgeFactory,
        finalizationService = scanFinalizationService,
        runtimeCoordinator = runtimeCoordinator,
        rawPathSettlementBarrier = rawPathSettlementBarrier,
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
    pilotBucketLabels: List<String> = listOf("control:neutral:success"),
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
                            family = "quic_realistic_burst",
                            outcome = "success",
                            rationale = "best",
                            succeededTargets = quicSucceededTargets,
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
                pilotBucketLabels = pilotBucketLabels,
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
