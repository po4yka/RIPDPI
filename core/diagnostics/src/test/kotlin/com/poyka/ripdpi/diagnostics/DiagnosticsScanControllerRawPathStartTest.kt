package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.RawPathExecutionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsScanControllerRawPathStartTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `raw-path scan starts bridge inside raw path runner`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json)
            var rawPathRunnerEntered = false
            val runtimeCoordinator =
                object : DiagnosticsRuntimeCoordinator {
                    override suspend fun runRawPathScan(block: suspend () -> Unit): RawPathExecutionResult {
                        rawPathRunnerEntered = true
                        assertNull(bridgeFactory.bridge.startedRequestJson)
                        block()
                        assertTrue(bridgeFactory.bridge.startedRequestJson != null)
                        return completedRawPathExecutionResult()
                    }

                    override suspend fun runAutomaticRawPathScan(block: suspend () -> Unit): RawPathExecutionResult {
                        block()
                        return completedRawPathExecutionResult()
                    }
                }
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

            val result = services.scanController.startScan(ScanPathMode.RAW_PATH)
            assertTrue(result is DiagnosticsManualScanStartResult.Started)
            val sessionId = (result as DiagnosticsManualScanStartResult.Started).sessionId
            advanceUntilIdle()

            assertTrue(rawPathRunnerEntered)
            assertEquals("completed", stores.getScanSession(sessionId)?.status)
        }
}
