package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsScanControllerInPathPreflightTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `in-path scan launch fails before bridge when proxy service is halted`() =
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
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(serviceStatus = "Halted"),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = runtimeCoordinator,
                    serviceStateStore = FakeServiceStateStore(AppStatus.Halted to Mode.VPN),
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            val failure =
                assertSuspendFailsWith<IllegalStateException> {
                    services.scanController.startScan(ScanPathMode.IN_PATH)
                }
            advanceUntilIdle()

            val expectedSummary =
                "In-path diagnostics unavailable: local proxy service is Halted; " +
                    "start the RIPDPI service before scanning"
            assertEquals(
                expectedSummary,
                failure.message,
            )
            assertNull(bridgeFactory.bridge.startedRequestJson)
            val session = stores.sessionsState.value.single()
            assertEquals("failed", session.status)
            assertEquals(failure.message, session.summary)
            assertEquals(0, runtimeCoordinator.rawScanCount.get())
        }
}

private suspend inline fun <reified T : Throwable> assertSuspendFailsWith(noinline block: suspend () -> Unit): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) {
            return error
        }
        throw error
    }
    throw AssertionError("Expected ${T::class.java.simpleName} to be thrown")
}
