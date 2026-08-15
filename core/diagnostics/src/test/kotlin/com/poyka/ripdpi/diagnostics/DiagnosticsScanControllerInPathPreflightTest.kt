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

    @Test
    fun `in-path scan launch fails before bridge when VPN service owns runtime proxy`() =
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
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(activeMode = "VPN"),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = runtimeCoordinator,
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN),
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
                "In-path diagnostics unavailable while VPN service is active; " +
                    "run raw-path diagnostics so the VPN can pause and resume safely"
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

    @Test
    fun `owned in-path start rejects a runtime that changed after context capture without persisting a session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json)
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(activeMode = "Proxy"),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(AppStatus.Halted to Mode.Proxy),
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            val failure =
                assertSuspendFailsWith<InPathRuntimeUnavailableException> {
                    services.scanController.startScanOwnedBy(
                        ownerId = "home-run",
                        pathMode = ScanPathMode.IN_PATH,
                        selectedProfileId = null,
                        skipActiveScanCheck = true,
                    )
                }
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "In-path diagnostics unavailable: local proxy service is Halted; " +
                        "start the RIPDPI service before scanning",
                    null,
                    0,
                ),
                listOf(failure.message, bridgeFactory.bridge.startedRequestJson, stores.sessionsState.value.size),
            )
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
