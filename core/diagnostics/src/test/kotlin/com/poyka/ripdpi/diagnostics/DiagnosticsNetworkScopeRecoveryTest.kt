package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.DiagnosticsProxyCredentials
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsNetworkScopeRecoveryTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `partial persistence retains measurements and removes authority after epoch changes`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val epochProvider = FakeDiagnosticsNetworkEpochProvider()
            val settings = defaultDiagnosticsAppSettings()
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "partial-network-change",
                    settings = settings,
                    networkEpochProvider = epochProvider,
                    profileId = "automatic-probing",
                )
            seedPreparedScan(stores, prepared)
            val original = scanReportWithStrategyProbe(prepared.sessionId, settings)
            epochProvider.epoch = FakeDiagnosticsNetworkEpoch(2)

            persistPartialScanSession(
                prepared.initialSession,
                json.encodeToString(EngineScanReportWire.serializer(), original.toEngineScanReportWire()),
                prepared,
                stores,
                json,
            )

            val session = requireNotNull(stores.getScanSession(prepared.sessionId))
            assertEquals("completed", session.status)
            val report = json.decodeEngineScanReportWire(requireNotNull(session.reportJson)).toScanReport()
            assertRecoveryReportHasNoAuthority(original, report)
        }

    @Test
    fun `malformed partial report without prepared scope is dropped safely`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val prepared = preparedDiagnosticsScan("malformed-partial", defaultDiagnosticsAppSettings())
            seedPreparedScan(stores, prepared)

            persistPartialScanSession(
                prepared.initialSession,
                "{\"strategyProbeReport\":",
                null,
                stores,
                json,
            )

            val session = requireNotNull(stores.getScanSession(prepared.sessionId))
            assertEquals("completed", session.status)
            assertNull(session.reportJson)
            assertTrue(stores.rememberedPoliciesState.value.isEmpty())
        }

    @Test
    fun `in path terminal fallback removes authority when persistence fails during network switch`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val epochProvider = FakeDiagnosticsNetworkEpochProvider()
            val settings = defaultDiagnosticsAppSettings()
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = coordinatorTimelineSource(stores, backgroundScope),
                    serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                    json = json,
                )
            val rawPrepared =
                preparedDiagnosticsScan(
                    sessionId = "in-path-fallback-network-change",
                    settings = settings,
                    networkEpochProvider = epochProvider,
                    profileId = "automatic-probing",
                )
            val prepared =
                rawPrepared.copy(
                    pathMode = ScanPathMode.IN_PATH,
                    initialSession = rawPrepared.initialSession.copy(pathMode = ScanPathMode.IN_PATH.name),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val original =
                scanReportWithStrategyProbe(prepared.sessionId, settings).copy(pathMode = ScanPathMode.IN_PATH)
            val bridge = networkScopeRecoveryBridge(original)
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            var failedPersistenceAttempts = 0
            stores.beforePersistCompletedScan = {
                failedPersistenceAttempts += 1
                epochProvider.epoch = FakeDiagnosticsNetworkEpoch(2)
                error("injected terminal persistence failure")
            }

            fixtures.coordinator.execute(
                prepared,
                BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge),
                rawPathRunner = ::runSettledRawPathBlock,
            )

            val session = requireNotNull(stores.getScanSession(prepared.sessionId))
            assertEquals("failed", session.status)
            assertTrue(failedPersistenceAttempts >= 2)
            val report = json.decodeEngineScanReportWire(requireNotNull(session.reportJson)).toScanReport()
            assertRecoveryReportHasNoAuthority(original, report)
            assertTrue(stores.rememberedPoliciesState.value.isEmpty())
            assertEquals(1, bridge.destroyCount)
            assertFalse(fixtures.activeScanRegistry.hasActiveScan())
        }

    @Test
    fun `corrected DNS reprobe never starts after network changes during VPN resume wait`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedStrategyProbeProfile(json) }
            val epochProvider = FakeDiagnosticsNetworkEpochProvider()
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Halted to Mode.VPN)
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json).apply { bridge.autoCompleteOnStart = false }
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = coordinatorTimelineSource(stores, backgroundScope),
                    serviceStateStore = serviceStateStore,
                    json = json,
                    bridgeFactory = bridgeFactory,
                    controllerScope = backgroundScope,
                )
            fixtures.runtimeCoordinator.updateInPathRouteLease(
                DiagnosticsInPathRouteLease(
                    runtimeId = "vpn-runtime",
                    routeGeneration = 1,
                    issuedRevision = 1L,
                    host = "127.0.0.1",
                    port = 19_080,
                    credentials = DiagnosticsProxyCredentials("diagnostics", "bounded-secret"),
                ),
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
                    sessionId = "network-change-before-reprobe",
                    settings = settings,
                    networkEpochProvider = epochProvider,
                    exposeProgress = false,
                    registerActiveBridge = false,
                    kind = ScanKind.STRATEGY_PROBE,
                    profileId = "automatic-probing",
                    family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                    strategyProbeRequest = StrategyProbeRequest(suiteId = "quick_v1"),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val bridge =
                networkScopeRecoveryBridge(
                    scanReportWithDnsFallbackResolverRecommendation(prepared.sessionId, settings),
                )
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val execution =
                backgroundScope.launch {
                    fixtures.coordinator.execute(
                        prepared,
                        BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge),
                        rawPathRunner = ::runSettledRawPathBlock,
                    )
                }
            runCurrent()
            assertTrue(fixtures.activeScanRegistry.hasHiddenActiveScan)
            assertNull(bridgeFactory.bridge.startedRequestJson)

            epochProvider.epoch = FakeDiagnosticsNetworkEpoch(2)
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            execution.join()

            assertNull(bridgeFactory.bridge.startedRequestJson)
            val reprobe = stores.sessionsState.value.single { it.id != prepared.sessionId }
            assertEquals("failed", reprobe.status)
            assertTrue(reprobe.summary.contains("Network changed"))
            assertEquals(1, bridgeFactory.bridge.destroyCount)
            assertFalse(fixtures.activeScanRegistry.hasActiveScan())
        }
}

private fun networkScopeRecoveryBridge(report: ScanReport): FakeNetworkDiagnosticsBridge =
    FakeNetworkDiagnosticsBridge(diagnosticsTestJson()).apply {
        autoCompleteOnStart = false
        enqueueProgress(
            ScanProgress(
                sessionId = report.sessionId,
                phase = "complete",
                completedSteps = 1,
                totalSteps = 1,
                message = "complete",
                isFinished = true,
            ),
        )
        enqueueReport(report)
    }

private fun assertRecoveryReportHasNoAuthority(
    original: ScanReport,
    report: ScanReport,
) {
    assertEquals(original.results, report.results)
    assertEquals(ScanCompletionKind.PARTIAL_RESULTS, report.completionKind)
    assertNull(report.resolverRecommendation)
    assertNull(report.strategyRecommendation)
    val strategy = requireNotNull(report.strategyProbeReport)
    assertNull(strategy.recommendation)
    assertEquals(StrategyProbeCompletionKind.PARTIAL_RESULTS, strategy.completionKind)
    assertTrue(report.diagnoses.any { it.code == "network_scope_unverified" })
}
