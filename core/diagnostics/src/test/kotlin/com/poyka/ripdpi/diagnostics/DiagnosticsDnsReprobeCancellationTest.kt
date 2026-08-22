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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsDnsReprobeCancellationTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `owned dns corrected reprobe retains terminal report when finalization fails`() =
        runTest {
            val scenario = ownedReprobeCancellationScenario(backgroundScope)
            val execution =
                backgroundScope.launch {
                    scenario.fixtures.coordinator.execute(
                        scenario.prepared,
                        BridgeSessionHandle(
                            scenario.originalBridge,
                            scenario.prepared.sessionId,
                            scenario.prepared.registerActiveBridge,
                        ),
                        rawPathRunner = ::runSettledRawPathBlock,
                    )
                }

            runCurrent()

            val reprobeSessionId =
                scenario.fixtures.activeScanRegistry.sessionOwnership
                    .activeSessionIds("home-run")
                    .also { sessionIds ->
                        assertTrue(
                            "Expected owned DNS re-probe; sessions=${scenario.stores.sessionsState.value}",
                            sessionIds.isNotEmpty(),
                        )
                    }.single()
            assertTrue(scenario.fixtures.activeScanRegistry.hiddenAutomaticProbeActive.value)
            scenario.bridgeFactory.bridge.enqueueReport(
                ScanReport(
                    sessionId = reprobeSessionId,
                    profileId = "automatic-probing",
                    pathMode = ScanPathMode.IN_PATH,
                    startedAt = 30L,
                    finishedAt = 40L,
                    summary = "Partial re-probe",
                    results = listOf(ProbeResult("dns", "partial-reprobe.example", "reachable")),
                ),
            )
            var finalizationAttempts = 0
            scenario.stores.beforePersistCompletedScan = { session ->
                if (session.id == reprobeSessionId && finalizationAttempts++ == 0) {
                    error("injected terminal finalization failure")
                }
            }

            scenario.fixtures.scanController.cancelScan(reprobeSessionId)
            runCurrent()

            assertCompletedPartialCancellation(
                stores = scenario.stores,
                sessionId = reprobeSessionId,
                bridge = scenario.bridgeFactory.bridge,
                execution = execution,
                registry = scenario.fixtures.activeScanRegistry,
            )
        }

    private suspend fun ownedReprobeCancellationScenario(
        scope: kotlinx.coroutines.CoroutineScope,
    ): OwnedReprobeScenario {
        val stores = FakeDiagnosticsHistoryStores().apply { seedStrategyProbeProfile(json) }
        val clock = TestDiagnosticsHistoryClock()
        val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json).apply { bridge.autoCompleteOnStart = false }
        val fixtures =
            executionCoordinatorFixtures(
                stores = stores,
                timelineSource = coordinatorTimelineSource(stores, scope),
                serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                json = json,
                bridgeFactory = bridgeFactory,
            )
        fixtures.runtimeCoordinator.updateInPathRouteLease(testInPathRouteLease())
        val settings = testDnsFallbackSettings()
        val prepared = testOwnedReprobePreparedScan(settings)
        seedPreparedScan(stores, prepared)
        fixtures.activeScanRegistry.rememberPreparedScan(prepared, ownerId = "home-run")
        val originalBridge = dnsFallbackBridge(prepared.sessionId, settings)
        fixtures.activeScanRegistry.registerBridge(
            originalBridge,
            prepared.sessionId,
            prepared.registerActiveBridge,
        )
        return OwnedReprobeScenario(stores, fixtures, bridgeFactory, prepared, originalBridge)
    }

    private fun testDnsFallbackSettings() =
        defaultDiagnosticsAppSettings()
            .toBuilder()
            .setDnsMode(DnsModePlainUdp)
            .setDnsProviderId(DnsProviderCustom)
            .setDnsIp("8.8.8.8")
            .build()

    private suspend fun testOwnedReprobePreparedScan(settings: com.poyka.ripdpi.proto.AppSettings) =
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
                        rawPathRunner = ::runSettledRawPathBlock,
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

    @Test
    fun `pending reprobe reserves hidden slot before vpn resume so admission rejects concurrent starts`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedStrategyProbeProfile(json) }
            val clock = TestDiagnosticsHistoryClock()
            // Halted service parks the re-probe inside waitForVpnServiceResume.
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Halted to Mode.VPN)
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json).apply { bridge.autoCompleteOnStart = false }
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = coordinatorTimelineSource(stores, backgroundScope),
                    serviceStateStore = serviceStateStore,
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                    bridgeFactory = bridgeFactory,
                )
            fixtures.runtimeCoordinator.updateInPathRouteLease(testInPathRouteLease())
            val settings = testDnsFallbackSettings()
            val prepared = testOwnedReprobePreparedScan(settings)
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared, ownerId = "admission-home-run")
            val originalBridge = dnsFallbackBridge(prepared.sessionId, settings)
            fixtures.activeScanRegistry.registerBridge(
                originalBridge,
                prepared.sessionId,
                prepared.registerActiveBridge,
            )
            backgroundScope.launch {
                fixtures.coordinator.execute(
                    prepared,
                    BridgeSessionHandle(originalBridge, prepared.sessionId, prepared.registerActiveBridge),
                    rawPathRunner = ::runSettledRawPathBlock,
                )
            }

            var reservedDuringResumeWait = false
            repeat(40) {
                testScheduler.advanceTimeBy(250)
                runCurrent()
                if (fixtures.activeScanRegistry.hasHiddenActiveScan) {
                    reservedDuringResumeWait = true
                }
            }
            assertTrue(
                "Re-probe must reserve the hidden scan slot while waiting for VPN resume",
                reservedDuringResumeWait,
            )
            assertTrue(serviceStateStore.status.value.first != AppStatus.Running)

            val admission = ScanAdmissionService(FakeAppSettingsRepository(), stores, fixtures.activeScanRegistry, json)
            assertNull(
                "Admission must reject an automatic probe while a DNS-corrected re-probe is pending",
                admission.admitAutomaticProbe(settings),
            )

            // The reservation is released once the resume wait times out and cleanup
            // runs; afterwards the same admission call succeeds, proving the rejection
            // above was caused by the pending re-probe rather than static policy.
            repeat(30) {
                testScheduler.advanceTimeBy(500)
                runCurrent()
            }
            assertFalse(fixtures.activeScanRegistry.hasActiveScan())
            assertTrue(admission.admitAutomaticProbe(settings) != null)
            assertTrue(admission.admitAutomaticProbe(settings) != null)
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

private data class OwnedReprobeScenario(
    val stores: FakeDiagnosticsHistoryStores,
    val fixtures: ExecutionCoordinatorFixtures,
    val bridgeFactory: FakeNetworkDiagnosticsBridgeFactory,
    val prepared: PreparedDiagnosticsScan,
    val originalBridge: FakeNetworkDiagnosticsBridge,
)
