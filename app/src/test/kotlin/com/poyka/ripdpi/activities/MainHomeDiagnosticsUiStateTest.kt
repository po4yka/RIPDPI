package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectModeVerdictResult
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeOutcome
import com.poyka.ripdpi.diagnostics.DirectModeVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

class MainHomeDiagnosticsUiStateTest {
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
