package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeAuditOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeVerificationOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainHomeDiagnosticsActionsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `verified vpn flow starts in-path verification scan`() =
        runTest {
            val effects = Channel<MainEffect>(Channel.BUFFERED)
            val diagnosticsTimelineSource = StubDiagnosticsTimelineSource()
            val diagnosticsScanController =
                StubDiagnosticsScanController().apply {
                    startResults += DiagnosticsManualScanStartResult.Started("verify-session")
                }
            val diagnosticsHomeWorkflowService =
                StubDiagnosticsHomeWorkflowService().apply {
                    currentFingerprint = "fp-1"
                    verificationOutcomes["verify-session"] =
                        DiagnosticsHomeVerificationOutcome(
                            sessionId = "verify-session",
                            success = true,
                            headline = "VPN access confirmed",
                            summary = "Traffic is reaching the network.",
                            detail = "HTTP and HTTPS probes passed.",
                        )
                }
            val serviceStateStore = FakeServiceStateStore()
            val runtimeState = MutableStateFlow(ConnectionRuntimeState())
            val permissionState =
                MutableStateFlow(
                    PermissionRuntimeState(
                        snapshot =
                            PermissionSnapshot(
                                vpnConsent = PermissionStatus.Granted,
                                notifications = PermissionStatus.Granted,
                                batteryOptimization = PermissionStatus.Granted,
                            ),
                    ),
                )
            val homeDiagnosticsState =
                MutableStateFlow(
                    HomeDiagnosticsRuntimeState(
                        latestAuditOutcome =
                            DiagnosticsHomeAuditOutcome(
                                sessionId = "audit-session",
                                fingerprintHash = "fp-1",
                                actionable = true,
                                headline = "Applied",
                                summary = "Ready to verify",
                            ),
                        currentFingerprintHash = "fp-1",
                        waitingForVerifiedVpnStart = true,
                    ),
                )
            val actions =
                MainHomeDiagnosticsActions(
                    mutations =
                        MainMutationRunner(
                            scope = backgroundScope,
                            effects = effects,
                            currentUiState = { MainUiState() },
                        ),
                    diagnosticsTimelineSource = diagnosticsTimelineSource,
                    diagnosticsScanController = diagnosticsScanController,
                    diagnosticsShareService = StubDiagnosticsShareService(),
                    diagnosticsHomeWorkflowService = diagnosticsHomeWorkflowService,
                    serviceStateStore = serviceStateStore,
                    runtimeState = runtimeState,
                    permissionState = permissionState,
                    homeDiagnosticsState = homeDiagnosticsState,
                    stringResolver = FakeStringResolver(),
                    requestVpnStart = {},
                )

            actions.initialize()
            advanceUntilIdle()

            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runtimeState.value = ConnectionRuntimeState(connectionState = ConnectionState.Connected)
            runCurrent()

            assertTrue(
                diagnosticsScanController.startedRequests.any { (pathMode, profileId) ->
                    pathMode == ScanPathMode.IN_PATH && profileId == "default"
                },
            )
            advanceUntilIdle()
            assertEquals("verify-session", homeDiagnosticsState.value.activeVerificationSessionId)
        }

    private fun completedSession(
        id: String,
        summary: String = "Verification complete",
    ): DiagnosticScanSession =
        DiagnosticScanSession(
            id = id,
            profileId = "default",
            pathMode = ScanPathMode.IN_PATH.name,
            serviceMode = "VPN",
            status = "completed",
            summary = summary,
            startedAt = 10L,
            finishedAt = 20L,
        )
}
