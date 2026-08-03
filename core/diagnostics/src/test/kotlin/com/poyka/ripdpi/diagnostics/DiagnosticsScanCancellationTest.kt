package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.testing.FaultOutcome
import com.poyka.ripdpi.core.testing.FaultSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsScanCancellationTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `cancel persists the native partial report before execution cleanup`() =
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

            val started = services.scanController.startScan(ScanPathMode.RAW_PATH)
            val sessionId = (started as DiagnosticsManualScanStartResult.Started).sessionId
            bridgeFactory.bridge.enqueueReport(
                ScanReport(
                    sessionId = sessionId,
                    profileId = "default",
                    pathMode = ScanPathMode.RAW_PATH,
                    startedAt = 10L,
                    finishedAt = 20L,
                    summary = "Partial result",
                    results = listOf(ProbeResult("dns", "partial.example", "reachable")),
                ),
            )

            services.scanController.cancelActiveScan()

            val cancelledSession = requireNotNull(stores.getScanSession(sessionId))
            assertEquals("completed", cancelledSession.status)
            assertEquals("Scan completed with partial results", cancelledSession.summary)
            assertTrue(cancelledSession.reportJson?.contains("partial.example") == true)
            assertFalse(cancelledSession.summary.startsWith("{"))
            assertEquals(1, bridgeFactory.bridge.destroyCount)
        }

    @Test
    fun `native cancellation failure still persists a terminal session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                    bridge.faults.enqueue(
                        FaultSpec(
                            target = DiagnosticsBridgeFaultTarget.CANCEL,
                            outcome = FaultOutcome.EXCEPTION,
                            message = "cancel failed",
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

            val started = services.scanController.startScan(ScanPathMode.RAW_PATH)
            val sessionId = (started as DiagnosticsManualScanStartResult.Started).sessionId
            val failure = runCatching { services.scanController.cancelActiveScan() }.exceptionOrNull()

            assertTrue(failure is IOException)
            val cancelledSession = requireNotNull(stores.getScanSession(sessionId))
            assertEquals("failed", cancelledSession.status)
            assertEquals("Diagnostics scan canceled", cancelledSession.summary)
            assertEquals(1, bridgeFactory.bridge.destroyCount)
            assertFalse(services.scanController.hasActiveScan())
        }

    @Test
    fun `native partial report failure surfaces through cancellation`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                    bridge.faults.enqueue(
                        FaultSpec(
                            target = DiagnosticsBridgeFaultTarget.TAKE_REPORT,
                            outcome = FaultOutcome.EXCEPTION,
                            message = "take report failed",
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

            val started = services.scanController.startScan(ScanPathMode.RAW_PATH)
            val sessionId = (started as DiagnosticsManualScanStartResult.Started).sessionId
            val failure = runCatching { services.scanController.cancelActiveScan() }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals("take report failed", failure?.message)
            assertEquals("failed", requireNotNull(stores.getScanSession(sessionId)).status)
            assertEquals(1, bridgeFactory.bridge.destroyCount)
        }
}
