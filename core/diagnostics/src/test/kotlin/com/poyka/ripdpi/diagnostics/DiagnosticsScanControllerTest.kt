package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.testing.FaultOutcome
import com.poyka.ripdpi.core.testing.FaultSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsScanControllerTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `in-path scan launch injects proxy settings into request`() =
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

            val sessionId = services.scanController.startScan(ScanPathMode.IN_PATH)
            advanceUntilIdle()
            val request =
                json.decodeFromString(
                    ScanRequest.serializer(),
                    requireNotNull(bridgeFactory.bridge.startedRequestJson),
                )

            assertEquals(ScanPathMode.IN_PATH, request.pathMode)
            assertEquals("127.0.0.1", request.proxyHost)
            assertEquals(1080, request.proxyPort)
            assertEquals("completed", stores.getScanSession(sessionId)?.status)
            assertEquals(0, runtimeCoordinator.rawScanCount.get())
        }

    @Test
    fun `set active profile validates profile existence before updating settings`() =
        runTest {
            val appSettingsRepository = FakeAppSettingsRepository()
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = appSettingsRepository,
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json),
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            assertSuspendFailsWith<IllegalArgumentException> {
                services.scanController.setActiveProfile("missing-profile")
            }

            services.scanController.setActiveProfile("default")
            assertEquals("default", appSettingsRepository.snapshot().diagnosticsActiveProfileId)
        }

    @Test
    fun `duplicate active scan requests are rejected`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                }
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                    scope = backgroundScope,
                    controllerScope = backgroundScope,
                    json = json,
                )

            services.scanController.startScan(ScanPathMode.RAW_PATH)

            assertSuspendFailsWith<IllegalStateException> {
                services.scanController.startScan(ScanPathMode.IN_PATH)
            }
            services.scanController.cancelActiveScan()
        }

    @Test
    fun `start failure marks session failed and clears active progress`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.faults.enqueue(
                        FaultSpec(
                            target = DiagnosticsBridgeFaultTarget.START_SCAN,
                            outcome = FaultOutcome.EXCEPTION,
                            message = "boom",
                        ),
                    )
                }
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            assertSuspendFailsWith<java.io.IOException> {
                services.scanController.startScan(ScanPathMode.RAW_PATH)
            }

            val failedSession = stores.sessionsState.value.single()
            assertEquals("failed", failedSession.status)
            assertEquals("boom", failedSession.summary)
            assertNull(services.timelineSource.activeScanProgress.value)
            assertEquals(1, bridgeFactory.bridge.destroyCount)
        }

    @Test
    fun `cancel clears active progress and forwards cancel to the active bridge`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                }
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            services.scanController.startScan(ScanPathMode.RAW_PATH)
            services.scanController.cancelActiveScan()

            val canceledSession = stores.sessionsState.value.single()
            assertNull(services.timelineSource.activeScanProgress.value)
            assertEquals("failed", canceledSession.status)
            assertEquals("Diagnostics scan canceled", canceledSession.summary)
            assertFalse(services.scanController.hasActiveScan())
            assertEquals(1, bridgeFactory.bridge.cancelCount)
            assertEquals(1, bridgeFactory.bridge.destroyCount)
        }

    @Test
    fun `poll failure during scan execution marks session failed and cleans up`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val bridgeFactory =
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
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            val sessionId = services.scanController.startScan(ScanPathMode.RAW_PATH)
            advanceUntilIdle()

            val failedSession = stores.getScanSession(sessionId)
            assertEquals("failed", failedSession?.status)
            assertNull(services.timelineSource.activeScanProgress.value)
            assertEquals(1, bridgeFactory.bridge.destroyCount)
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
    fail("Expected ${T::class.java.simpleName} to be thrown")
    throw AssertionError("Unreachable")
}
