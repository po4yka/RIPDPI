package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanOrigin
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsNetworkScopeFinalizationTest {
    @Test
    fun `stable network retains strategy recommendation and remembered policy`() =
        runTest {
            val outcome = finalizeNetworkScopeScenario(NetworkScopeChange.Stable)

            assertEquals(1, outcome.stores.rememberedPoliciesState.value.size)
            assertNotNull(requireNotNull(outcome.report.strategyProbeReport).recommendation)
            assertEquals(outcome.original.results, outcome.report.results)
        }

    @Test
    fun `missing epoch preserves measurements without remembered strategy`() =
        runTest {
            assertUntrustedNetworkScope(finalizeNetworkScopeScenario(NetworkScopeChange.Missing))
        }

    @Test
    fun `return to the same fingerprint with another epoch cannot remember a strategy`() =
        runTest {
            assertUntrustedNetworkScope(finalizeNetworkScopeScenario(NetworkScopeChange.Returned))
        }

    @Test
    fun `network change while report persistence suspends removes strategy authority`() =
        runTest {
            assertUntrustedNetworkScope(finalizeNetworkScopeScenario(NetworkScopeChange.DuringPersistence))
        }

    @Test
    fun `stable network applies resolver override and remembers DNS path`() =
        runTest {
            val outcome = finalizeNetworkScopeScenario(NetworkScopeChange.Stable, resolverOnly = true)

            assertNotNull(outcome.resolverOverrideStore.override.value)
            assertEquals(1, outcome.stores.networkDnsPathPreferencesState.value.size)
            assertTrue(requireNotNull(outcome.report.resolverRecommendation).appliedTemporarily)
            assertEquals(outcome.original.results, outcome.report.results)
        }

    @Test
    fun `missing epoch prevents resolver override and remembered DNS path`() =
        runTest {
            assertUntrustedNetworkScope(finalizeNetworkScopeScenario(NetworkScopeChange.Missing, resolverOnly = true))
        }

    @Test
    fun `network change during persistence prevents resolver override and remembered DNS path`() =
        runTest {
            assertUntrustedNetworkScope(
                finalizeNetworkScopeScenario(NetworkScopeChange.DuringPersistence, resolverOnly = true),
            )
        }

    @Test
    fun `recovery report loses recommendation authority after epoch changes`() =
        runTest {
            assertUntrustedNetworkScope(finalizeNetworkScopeScenario(NetworkScopeChange.Returned, recovery = true))
        }
}

private enum class NetworkScopeChange { Stable, Missing, Returned, DuringPersistence }

private data class NetworkScopeOutcome(
    val stores: FakeDiagnosticsHistoryStores,
    val resolverOverrideStore: FakeResolverOverrideStore,
    val original: ScanReport,
    val report: ScanReport,
)

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LongMethod")
private suspend fun TestScope.finalizeNetworkScopeScenario(
    change: NetworkScopeChange,
    resolverOnly: Boolean = false,
    recovery: Boolean = false,
): NetworkScopeOutcome {
    val json = diagnosticsTestJson()
    val stores = FakeDiagnosticsHistoryStores()
    val resolverOverrideStore = FakeResolverOverrideStore()
    val epochProvider =
        FakeDiagnosticsNetworkEpochProvider(
            epoch = if (change == NetworkScopeChange.Missing) null else FakeDiagnosticsNetworkEpoch(1),
        )
    val fingerprint = strategyProbeFingerprint(ssid = "network-a", gateway = "192.0.2.10")
    val fingerprintProvider = MutableNetworkFingerprintProvider(fingerprint)
    val settings =
        defaultDiagnosticsAppSettings()
            .toBuilder()
            .setNetworkStrategyMemoryEnabled(true)
            .setDnsMode(DnsModePlainUdp)
            .setDnsIp("8.8.8.8")
            .build()
    val fixtures =
        executionCoordinatorFixtures(
            stores = stores,
            timelineSource = coordinatorTimelineSource(stores, backgroundScope),
            serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
            resolverOverrideStore = resolverOverrideStore,
            networkFingerprintProvider = fingerprintProvider,
            json = json,
        )
    val prepared =
        preparedDiagnosticsScan(
            sessionId = "network-scope-${change.name}",
            settings = settings,
            scanOrigin = DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
            exposeProgress = false,
            registerActiveBridge = false,
            networkFingerprint = fingerprint,
            networkEpochProvider = epochProvider,
            profileId = if (resolverOnly) "default" else "automatic-probing",
            family = if (resolverOnly) DiagnosticProfileFamily.GENERAL else DiagnosticProfileFamily.AUTOMATIC_PROBING,
            kind = if (resolverOnly) ScanKind.CONNECTIVITY else ScanKind.STRATEGY_PROBE,
            strategyProbeRequest = if (resolverOnly) null else StrategyProbeRequest(suiteId = "quick_v1"),
        )
    seedPreparedScan(stores, prepared)
    fixtures.activeScanRegistry.rememberPreparedScan(prepared)
    val original =
        if (resolverOnly) {
            scanReportWithResolverRecommendation(prepared.sessionId)
        } else {
            scanReportWithStrategyProbe(prepared.sessionId, settings)
        }
    if (change == NetworkScopeChange.Returned) {
        fingerprintProvider.fingerprint = strategyProbeFingerprint(ssid = "network-b", gateway = "192.0.2.20")
        epochProvider.epoch = FakeDiagnosticsNetworkEpoch(2)
        fingerprintProvider.fingerprint = fingerprint
        epochProvider.epoch = FakeDiagnosticsNetworkEpoch(3)
    }
    if (change == NetworkScopeChange.DuringPersistence) {
        stores.beforePersistCompletedScan = {
            fingerprintProvider.fingerprint = strategyProbeFingerprint(ssid = "network-b", gateway = "192.0.2.20")
            epochProvider.epoch = FakeDiagnosticsNetworkEpoch(2)
        }
    }
    if (recovery) {
        fixtures.finalizationService.persistRawPathRecoveryReport(
            prepared,
            json.encodeToString(EngineScanReportWire.serializer(), original.toEngineScanReportWire()),
        )
    } else {
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
                enqueueReport(original)
            }
        fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
        fixtures.coordinator.execute(
            prepared,
            BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge),
            rawPathRunner = ::runSettledRawPathBlock,
        )
    }
    val session = requireNotNull(stores.getScanSession(prepared.sessionId))
    if (!recovery) {
        assertEquals("completed", session.status)
        assertEquals(original.results.size, stores.nativeEventsState.value.count { it.subsystem == "diagnostics" })
    }
    val report = json.decodeEngineScanReportWire(requireNotNull(session.reportJson)).toScanReport()
    return NetworkScopeOutcome(stores, resolverOverrideStore, original, report)
}

private fun assertUntrustedNetworkScope(outcome: NetworkScopeOutcome) {
    assertTrue(
        outcome.stores.rememberedPoliciesState.value
            .isEmpty(),
    )
    assertTrue(
        outcome.stores.networkDnsPathPreferencesState.value
            .isEmpty(),
    )
    assertTrue(
        outcome.stores.networkEdgePreferencesState.value
            .isEmpty(),
    )
    assertNull(outcome.resolverOverrideStore.override.value)
    assertEquals(outcome.original.results, outcome.report.results)
    assertNull(outcome.report.resolverRecommendation)
    assertNull(outcome.report.strategyRecommendation)
    if (outcome.original.strategyProbeReport != null) {
        val strategy = requireNotNull(outcome.report.strategyProbeReport)
        assertNull(strategy.recommendation)
        assertEquals(StrategyProbeCompletionKind.PARTIAL_RESULTS, strategy.completionKind)
    }
    assertEquals(ScanCompletionKind.PARTIAL_RESULTS, outcome.report.completionKind)
    assertTrue(outcome.report.diagnoses.any { it.code == "network_scope_unverified" })
}
