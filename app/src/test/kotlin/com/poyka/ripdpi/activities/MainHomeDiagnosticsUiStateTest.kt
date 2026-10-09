package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectModeVerdictResult
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeOutcome
import com.poyka.ripdpi.diagnostics.DirectModeVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MainHomeDiagnosticsUiStateTest {
    @Test
    fun `unknown audit or current network gives explicit rerun reason`() {
        listOf(null to "fp-1", "fp-1" to null).forEach { (auditFingerprint, currentFingerprint) ->
            val uiState =
                buildHomeDiagnosticsUiState(
                    settings = AppSettingsSerializer.defaultValue,
                    appStatus = AppStatus.Halted,
                    connectionState = ConnectionState.Disconnected,
                    runtime =
                        HomeDiagnosticsRuntimeState(
                            latestCompositeOutcome =
                                DiagnosticsHomeCompositeOutcome(
                                    runId = "audit",
                                    fingerprintHash = auditFingerprint,
                                    actionable = true,
                                    headline = "Completed",
                                    summary = "Completed checks",
                                ),
                            currentFingerprintHash = currentFingerprint,
                        ),
                    stringResolver = FakeStringResolver(),
                )
            assertFalse(uiState.verifiedVpnAction.enabled)
            assertEquals(R.string.home_diagnostics_run_again.toString(), uiState.verifiedVpnAction.supportingText)
        }
    }

    @Test
    fun `connected connecting and reconnecting transitions keep verified start disabled with reason`() {
        val states =
            listOf(
                AppStatus.Halted to ConnectionState.Connected,
                AppStatus.Halted to ConnectionState.Connecting,
                AppStatus.Reconnecting to ConnectionState.Disconnected,
                AppStatus.Running to ConnectionState.Disconnected,
            )
        states.forEach { (status, connection) ->
            val uiState =
                buildHomeDiagnosticsUiState(
                    settings = AppSettingsSerializer.defaultValue,
                    appStatus = status,
                    connectionState = connection,
                    runtime =
                        HomeDiagnosticsRuntimeState(
                            latestCompositeOutcome =
                                DiagnosticsHomeCompositeOutcome(
                                    runId = "audit",
                                    fingerprintHash = "fp-1",
                                    actionable = true,
                                    headline = "Completed",
                                    summary = "Completed checks",
                                ),
                            currentFingerprintHash = "fp-1",
                        ),
                    stringResolver = FakeStringResolver(),
                )
            assertFalse(uiState.verifiedVpnAction.enabled)
            assertEquals(
                R.string.home_diagnostics_disconnect_first.toString(),
                uiState.verifiedVpnAction.supportingText,
            )
        }
    }

    @Test
    fun `cancelled analysis start is exposed as a terminal cancelled state`() {
        val uiState =
            buildHomeDiagnosticsUiState(
                settings = AppSettingsSerializer.defaultValue,
                appStatus = AppStatus.Halted,
                connectionState = ConnectionState.Disconnected,
                runtime = HomeDiagnosticsRuntimeState(analysisStartCancelled = true),
                stringResolver = FakeStringResolver(),
            )

        assertEquals(HomeDiagnosticsRunUiStatus.CANCELLED, uiState.analysisRunStatus)
    }

    @Test
    fun `buildHomeDiagnosticsUiState exposes latest manual scan when no composite audit exists`() {
        val uiState =
            buildHomeDiagnosticsUiState(
                settings = AppSettingsSerializer.defaultValue,
                appStatus = AppStatus.Halted,
                connectionState = ConnectionState.Disconnected,
                runtime =
                    HomeDiagnosticsRuntimeState(
                        latestManualDiagnosticSession =
                            DiagnosticScanSession(
                                id = "manual-session",
                                profileId = "default",
                                pathMode = "RAW_PATH",
                                serviceMode = null,
                                status = "completed",
                                summary = "18 completed · 18 healthy",
                                startedAt = 10L,
                                finishedAt = 20L,
                            ),
                    ),
                stringResolver = FakeStringResolver(),
            )

        assertEquals("18 completed · 18 healthy", uiState.latestAudit?.headline)
        assertEquals("default · RAW_PATH", uiState.latestAudit?.summary)
    }

    @Test
    fun `buildHomeDiagnosticsUiState exposes owned stack browser target from composite verdict`() {
        val uiState =
            buildHomeDiagnosticsUiState(
                settings = AppSettingsSerializer.defaultValue,
                appStatus = AppStatus.Halted,
                connectionState = ConnectionState.Disconnected,
                runtime =
                    HomeDiagnosticsRuntimeState(
                        latestCompositeOutcome =
                            DiagnosticsHomeCompositeOutcome(
                                runId = "home-run",
                                fingerprintHash = "fp-1",
                                actionable = true,
                                headline = "Owned stack required",
                                summary = "Open the owned-stack browser.",
                                directModeVerdict =
                                    DirectModeVerdict(
                                        result = DirectModeVerdictResult.OWNED_STACK_ONLY,
                                        reasonCode = DirectModeReasonCode.OWNED_STACK_REQUIRED,
                                        authority = "example.org:443",
                                    ),
                            ),
                        currentFingerprintHash = "fp-1",
                    ),
                stringResolver = FakeStringResolver(),
            )

        assertEquals("https://example.org:443/", uiState.latestAudit?.ownedStackLaunchUrl)
        assertEquals(
            DiagnosticsRemediationActionKindUiModel.OPEN_OWNED_STACK_BROWSER,
            uiState.remediationLadder?.primaryAction?.kind,
        )
        assertEquals("https://example.org:443/", uiState.remediationLadder?.primaryAction?.targetUrl)
    }
}
