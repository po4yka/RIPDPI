package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsHomeRunAdmissionTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `interstage lease rejects manual automatic and profile commands before native startup`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val settings = FakeAppSettingsRepository()
            val bridge = FakeNetworkDiagnosticsBridgeFactory(json)
            val lease = DiagnosticsHomeRunLease()
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = settings,
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridge,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                    homeRunLease = lease,
                )
            assertTrue(lease.acquire("home"))
            assertTrue(services.scanController.hasActiveHomeRun())
            assertFalse(services.scanController.hasActiveScan())
            assertNull(services.timelineSource.activeScanProgress.value)

            val manual =
                expectStartRejection {
                    services.scanController.startScan(ScanPathMode.RAW_PATH, skipActiveScanCheck = true)
                }
            val foreign =
                expectStartRejection {
                    services.scanController.startScanOwnedBy("other", ScanPathMode.RAW_PATH, skipActiveScanCheck = true)
                }
            val profile =
                expectStartRejection {
                    services.scanController.setActiveProfile("default")
                }
            assertEquals(DiagnosticsScanStartRejectionReason.ScanAlreadyActive, manual.reason)
            assertEquals(manual.reason, foreign.reason)
            assertEquals(manual.reason, profile.reason)
            assertEquals(
                AutomaticProbeLaunchOutcome.RETRY,
                services.scanController.launchAutomaticProbe(
                    settings.snapshot(),
                    FakeNetworkFingerprintProvider().transportSwitchHandoverEvent(),
                ),
            )
            assertNull(bridge.bridge.startedRequestJson)
            assertTrue(stores.sessionsState.value.isEmpty())

            lease.release("home")
            services.scanController.setActiveProfile("default")
            assertFalse(services.scanController.hasActiveHomeRun())
            assertEquals("default", settings.snapshot().diagnosticsActiveProfileId)
        }

    @Test
    fun `matching owner starts its stage and stale owner is rejected after release or replacement`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val lease = DiagnosticsHomeRunLease()
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json),
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                    homeRunLease = lease,
                )
            lease.acquire("home")
            val started = services.scanController.startScanOwnedBy("home", ScanPathMode.RAW_PATH)
            assertTrue(started is DiagnosticsManualScanStartResult.Started)
            val sessionId = (started as DiagnosticsManualScanStartResult.Started).sessionId
            assertTrue(services.scanController.activeSessionIdsOwnedBy("home").contains(sessionId))
            services.scanController.releaseSessionsOwnedBy("home")
            lease.release("home")
            expectStartRejection {
                services.scanController.startScanOwnedBy("home", ScanPathMode.RAW_PATH, skipActiveScanCheck = true)
            }
            lease.acquire("replacement")
            lease.release("home")
            expectStartRejection {
                services.scanController.startScanOwnedBy("home", ScanPathMode.RAW_PATH, skipActiveScanCheck = true)
            }
            assertTrue(lease.isOwnedBy("replacement"))
        }

    private suspend fun expectStartRejection(block: suspend () -> Unit): DiagnosticsScanStartRejectedException {
        try {
            block()
        } catch (rejected: DiagnosticsScanStartRejectedException) {
            return rejected
        }
        throw AssertionError("Expected diagnostics start rejection")
    }
}
