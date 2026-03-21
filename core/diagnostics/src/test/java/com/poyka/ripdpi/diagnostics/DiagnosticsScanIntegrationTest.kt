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
            val historyRepository = FakeDiagnosticsHistoryRepository().apply { seedDefaultProfile(json) }
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json)
            val runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator()
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    historyRepository = historyRepository,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = runtimeCoordinator,
                    serviceStateStore = FakeServiceStateStore(),
                    scope = this,
                    json = json,
                )

            val sessionId = services.scanController.startScan(ScanPathMode.RAW_PATH)
            advanceUntilIdle()

            val session = historyRepository.getScanSession(sessionId)
            assertEquals(1, runtimeCoordinator.rawScanCount.get())
            assertEquals("completed", session?.status)
            assertEquals(1, historyRepository.storedProbeResults(sessionId).size)
            assertEquals(2, historyRepository.snapshotsState.value.count { it.sessionId == sessionId })
            assertEquals(2, historyRepository.contextsState.value.count { it.sessionId == sessionId })
            assertTrue(historyRepository.nativeEventsState.value.any { it.sessionId == sessionId })
            assertEquals(1, bridgeFactory.bridge.destroyCount)
        }
}
