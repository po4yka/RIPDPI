package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.DiagnosticsProxyCredentials
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
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
    fun `in-path scan through a running VPN uses the current authenticated loopback route lease`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json)
            val routeLease =
                DiagnosticsInPathRouteLease(
                    runtimeId = "vpn-runtime-7",
                    routeGeneration = 42L,
                    issuedRevision = 1L,
                    host = "127.0.0.1",
                    port = 19_080,
                    credentials = DiagnosticsProxyCredentials("diagnostics", "bounded-secret"),
                )
            val runtimeCoordinator =
                object : DiagnosticsRuntimeCoordinator {
                    var rawScanCount = 0
                    var leaseValidationCount = 0

                    override suspend fun runRawPathScan(
                        block: suspend () -> Unit,
                    ): com.poyka.ripdpi.data.RawPathExecutionResult {
                        rawScanCount += 1
                        block()
                        return completedRawPathExecutionResult()
                    }

                    override suspend fun runAutomaticRawPathScan(
                        block: suspend () -> Unit,
                    ): com.poyka.ripdpi.data.RawPathExecutionResult {
                        rawScanCount += 1
                        block()
                        return completedRawPathExecutionResult()
                    }

                    override suspend fun acquireInPathRouteLease(): DiagnosticsInPathRouteLease = routeLease

                    override fun isInPathRouteLeaseCurrent(lease: DiagnosticsInPathRouteLease): Boolean {
                        leaseValidationCount += 1
                        return lease == routeLease
                    }
                }
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(activeMode = "VPN"),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = runtimeCoordinator,
                    serviceStateStore = serviceStateStore,
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            services.scanController.startScan(ScanPathMode.IN_PATH)
            advanceUntilIdle()

            val request =
                json.decodeFromString(
                    EngineScanRequestWire.serializer(),
                    requireNotNull(bridgeFactory.bridge.startedRequestJson),
                )
            assertEquals(
                listOf(
                    ScanPathMode.IN_PATH,
                    "127.0.0.1",
                    19_080,
                    "diagnostics",
                    "bounded-secret",
                ),
                listOf(
                    request.pathMode,
                    request.inPathRoute?.host,
                    request.inPathRoute?.port,
                    request.inPathRoute?.credentials?.username,
                    request.inPathRoute?.credentials?.password,
                ),
            )
            assertEquals(4, runtimeCoordinator.leaseValidationCount)
            assertEquals(0, runtimeCoordinator.rawScanCount)
            assertEquals(AppStatus.Running to Mode.VPN, serviceStateStore.status.value)
        }

    @Test
    fun `route revoked during bridge creation is rejected before native start`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator()
            runtimeCoordinator.updateInPathRouteLease(
                DiagnosticsInPathRouteLease(
                    runtimeId = "vpn-bridge-race",
                    routeGeneration = 1,
                    issuedRevision = 1L,
                    host = "127.0.0.1",
                    port = 19_080,
                    credentials = DiagnosticsProxyCredentials("diagnostics", "bounded-secret"),
                ),
            )
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    beforeCreate = { runtimeCoordinator.updateInPathRouteLease(null) }
                }
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

            assertSuspendFailsWith<InPathRuntimeUnavailableException> {
                services.scanController.startScan(ScanPathMode.IN_PATH)
            }
            advanceUntilIdle()
            assertNull(bridgeFactory.bridge.startedRequestJson)
            assertEquals(1, bridgeFactory.bridge.destroyCount)
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
