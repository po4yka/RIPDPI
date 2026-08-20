package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.DiagnosticsProxyCredentials
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsDnsReprobeCancellationTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `owned dns corrected reprobe is cancelled and releases hidden admission`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedStrategyProbeProfile(json) }
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = coordinatorTimelineSource(stores, backgroundScope)
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json).apply { bridge.autoCompleteOnStart = false }
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                    bridgeFactory = bridgeFactory,
                )
            fixtures.runtimeCoordinator.updateInPathRouteLease(testInPathRouteLease())
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setDnsMode(DnsModePlainUdp)
                    .setDnsProviderId(DnsProviderCustom)
                    .setDnsIp("8.8.8.8")
                    .build()
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "owned-original",
                    settings = settings,
                    exposeProgress = false,
                    registerActiveBridge = false,
                    kind = ScanKind.STRATEGY_PROBE,
                    profileId = "automatic-probing",
                    family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                    strategyProbeRequest = StrategyProbeRequest(suiteId = "quick_v1"),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared, ownerId = "home-run")
            val originalBridge = dnsFallbackBridge(prepared.sessionId, settings)
            fixtures.activeScanRegistry.registerBridge(
                originalBridge,
                prepared.sessionId,
                prepared.registerActiveBridge,
            )
            val execution =
                backgroundScope.launch {
                    fixtures.coordinator.execute(
                        prepared,
                        BridgeSessionHandle(originalBridge, prepared.sessionId, prepared.registerActiveBridge),
                        rawPathRunner = { block -> block() },
                    )
                }

            runCurrent()

            val reprobeSessionId =
                fixtures.activeScanRegistry.sessionOwnership
                    .activeSessionIds("home-run")
                    .single()
            assertTrue(fixtures.activeScanRegistry.hiddenAutomaticProbeActive.value)
            bridgeFactory.bridge.enqueueReport(
                ScanReport(
                    sessionId = reprobeSessionId,
                    profileId = "automatic-probing",
                    pathMode = ScanPathMode.RAW_PATH,
                    startedAt = 30L,
                    finishedAt = 40L,
                    summary = "Partial re-probe",
                    results = listOf(ProbeResult("dns", "partial-reprobe.example", "reachable")),
                ),
            )

            fixtures.scanController.cancelScan(reprobeSessionId)
            runCurrent()

            assertCompletedPartialCancellation(
                stores = stores,
                sessionId = reprobeSessionId,
                bridge = bridgeFactory.bridge,
                execution = execution,
                registry = fixtures.activeScanRegistry,
            )
        }

    @Test
    fun `owner job cancellation finalizes reprobe before bridge registration`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedStrategyProbeProfile(json) }
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = coordinatorTimelineSource(stores, backgroundScope)
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json).apply { bridge.autoCompleteOnStart = false }
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                    bridgeFactory = bridgeFactory,
                    controllerScope = backgroundScope,
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
                    sessionId = "owned-startup-original",
                    settings = settings,
                    exposeProgress = false,
                    registerActiveBridge = false,
                    kind = ScanKind.STRATEGY_PROBE,
                    profileId = "automatic-probing",
                    family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                    strategyProbeRequest = StrategyProbeRequest(suiteId = "quick_v1"),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared, ownerId = "startup-home-run")
            val originalBridge = dnsFallbackBridge(prepared.sessionId, settings)
            fixtures.activeScanRegistry.registerBridge(
                originalBridge,
                prepared.sessionId,
                prepared.registerActiveBridge,
            )
            val reprobePersisted = CompletableDeferred<String>()
            stores.afterUpsertScanSession = { session ->
                if (session.id != prepared.sessionId && session.status == "running") {
                    reprobePersisted.complete(session.id)
                    awaitCancellation()
                }
            }
            val execution =
                backgroundScope.launch {
                    fixtures.coordinator.execute(
                        prepared,
                        BridgeSessionHandle(originalBridge, prepared.sessionId, prepared.registerActiveBridge),
                        rawPathRunner = { block -> block() },
                    )
                }

            val reprobeSessionId = reprobePersisted.await()
            execution.cancel()
            execution.join()
            runCurrent()

            val cancelledSession = requireNotNull(stores.getScanSession(reprobeSessionId))
            assertEquals("failed", cancelledSession.status)
            assertEquals("Diagnostics scan canceled during startup", cancelledSession.summary)
            assertTrue(execution.isCompleted)
            assertFalse(fixtures.activeScanRegistry.hasActiveScan())
            assertTrue(
                fixtures.activeScanRegistry.sessionOwnership
                    .activeSessionIds("startup-home-run")
                    .isEmpty(),
            )
        }

    private fun testInPathRouteLease() =
        DiagnosticsInPathRouteLease(
            runtimeId = "vpn-runtime",
            routeGeneration = 1,
            host = "127.0.0.1",
            port = 19_080,
            credentials = DiagnosticsProxyCredentials("diagnostics", "bounded-secret"),
        )

    private fun dnsFallbackBridge(
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
            enqueueReport(scanReportWithDnsFallbackResolverRecommendation(sessionId, settings))
        }

    private suspend fun assertCompletedPartialCancellation(
        stores: FakeDiagnosticsHistoryStores,
        sessionId: String,
        bridge: FakeNetworkDiagnosticsBridge,
        execution: Job,
        registry: ActiveScanRegistry,
    ) {
        val cancelledSession = requireNotNull(stores.getScanSession(sessionId))
        assertEquals("completed", cancelledSession.status)
        assertEquals("Scan completed with partial results", cancelledSession.summary)
        assertTrue(cancelledSession.reportJson?.contains("partial-reprobe.example") == true)
        assertEquals(1, bridge.cancelCount)
        assertEquals(1, bridge.destroyCount)
        assertFalse(registry.hiddenAutomaticProbeActive.value)
        assertFalse(registry.hasActiveScan())
        assertTrue(execution.isCompleted)
        assertTrue(registry.sessionOwnership.activeSessionIds("home-run").isEmpty())
    }
}
