package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsScanIntegrationTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `completed raw-path scan persists report results and passive artifacts end to end`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json)
            val runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator()
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = runtimeCoordinator,
                    serviceStateStore = FakeServiceStateStore(),
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            val sessionId =
                when (val result = services.scanController.startScan(ScanPathMode.RAW_PATH)) {
                    is DiagnosticsManualScanStartResult.Started -> {
                        result.sessionId
                    }

                    is DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution -> {
                        error("Expected started scan but got hidden probe conflict")
                    }
                }
            advanceUntilIdle()

            val session = stores.getScanSession(sessionId)
            assertEquals(1, runtimeCoordinator.rawScanCount.get())
            assertEquals("completed", session?.status)
            assertEquals(1, stores.storedProbeResults(sessionId).size)
            assertEquals(2, stores.snapshotsState.value.count { it.sessionId == sessionId })
            assertEquals(2, stores.contextsState.value.count { it.sessionId == sessionId })
            assertTrue(stores.nativeEventsState.value.any { it.sessionId == sessionId })
            assertEquals(1, bridgeFactory.bridge.destroyCount)
        }
}
