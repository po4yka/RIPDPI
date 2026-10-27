package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.testing.FaultOutcome
import com.poyka.ripdpi.core.testing.FaultSpec
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A scan whose failure cannot be written must not crash the process or stay "running". */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsScanFailurePersistenceTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `failure to persist a failed scan is contained and still cleans up`() =
        runTest {
            val stores = storesFailingFailedWrites { true }
            val bridgeFactory = pollCrashingBridgeFactory()
            val services = createServices(stores, bridgeFactory)

            services.scanController.startScan(ScanPathMode.IN_PATH).startedSessionId()
            advanceUntilIdle()

            assertFalse(services.scanController.hasActiveScan())
            assertEquals(1, bridgeFactory.bridge.destroyCount)
        }

    @Test
    fun `scan whose failure write fails once is still marked failed`() =
        runTest {
            var failedWrites = 0
            val stores = storesFailingFailedWrites { failedWrites++ == 0 }
            val services = createServices(stores, pollCrashingBridgeFactory())

            val sessionId = services.scanController.startScan(ScanPathMode.IN_PATH).startedSessionId()
            advanceUntilIdle()

            assertEquals("failed", stores.getScanSession(sessionId)?.status)
        }

    private fun storesFailingFailedWrites(shouldFail: () -> Boolean) =
        FakeDiagnosticsHistoryStores().apply {
            seedDefaultProfile(json)
            beforeUpsertScanSession = { session ->
                if (session.status == "failed" && shouldFail()) error("injected disk full")
            }
        }

    private fun pollCrashingBridgeFactory() =
        FakeNetworkDiagnosticsBridgeFactory(json).apply {
            bridge.autoCompleteOnStart = false
            bridge.faults.enqueue(
                FaultSpec(
                    target = DiagnosticsBridgeFaultTarget.POLL_PROGRESS,
                    outcome = FaultOutcome.EXCEPTION,
                    message = "poll crashed",
                ),
            )
        }

    private fun TestScope.createServices(
        stores: FakeDiagnosticsHistoryStores,
        bridgeFactory: FakeNetworkDiagnosticsBridgeFactory,
    ) = createDiagnosticsServices(
        context = TestContext(),
        appSettingsRepository = FakeAppSettingsRepository(),
        stores = stores,
        networkMetadataProvider = FakeNetworkMetadataProvider(),
        diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
        networkDiagnosticsBridgeFactory = bridgeFactory,
        runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
        serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy),
        scope = backgroundScope,
        controllerScope = this,
        json = json,
    )
}
