package com.poyka.ripdpi.activities

import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.diagnostics.DiagnosticsScanLaunchOrigin
import com.poyka.ripdpi.diagnostics.DiagnosticsScanLaunchTrigger
import com.poyka.ripdpi.diagnostics.DiagnosticsScanTriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsUiStateFactoryTest {
    private val support = DiagnosticsUiFactorySupport(ApplicationProvider.getApplicationContext())
    private val factory =
        DiagnosticsUiStateFactory(
            support = support,
            sessionDetailUiMapper = DiagnosticsSessionDetailUiFactory(support),
        )

    @Test
    fun `overview exposes recent background automatic probe when newer than manual sessions`() {
        val uiState =
            factory.buildUiState(
                input =
                    diagnosticsUiStateInput(
                        sessions =
                            listOf(
                                historyScanSession(
                                    id = "scan-auto",
                                    summary = "Automatic probe summary",
                                    startedAt = 10L,
                                    finishedAt = 20L,
                                    launchOrigin = DiagnosticsScanLaunchOrigin.AUTOMATIC_BACKGROUND,
                                    launchTrigger =
                                        DiagnosticsScanLaunchTrigger(
                                            type = DiagnosticsScanTriggerType.POLICY_HANDOVER,
                                            classification = "transport_switch",
                                            occurredAt = 9L,
                                            currentFingerprintHash = "fingerprint-b",
                                        ),
                                ),
                            ),
                    ),
            )

        val callout = requireNotNull(uiState.overview.recentAutomaticProbe)
        assertEquals("Automatic probe summary", callout.summary)
        assertNotNull(callout.detail)
    }

    @Test
    fun `overview omits recent automatic probe callout for manual only history`() {
        val uiState =
            factory.buildUiState(
                input =
                    diagnosticsUiStateInput(
                        sessions =
                            listOf(
                                historyScanSession(
                                    id = "scan-manual",
                                    summary = "Manual scan",
                                    launchOrigin = DiagnosticsScanLaunchOrigin.USER_INITIATED,
                                ),
                            ),
                    ),
            )

        assertNull(uiState.overview.recentAutomaticProbe)
    }

    @Test
    fun `overview suppresses background automatic probe callout when newer manual session exists`() {
        val uiState =
            factory.buildUiState(
                input =
                    diagnosticsUiStateInput(
                        sessions =
                            listOf(
                                historyScanSession(
                                    id = "scan-manual",
                                    summary = "Manual scan",
                                    startedAt = 30L,
                                    finishedAt = 40L,
                                    launchOrigin = DiagnosticsScanLaunchOrigin.USER_INITIATED,
                                ),
                                historyScanSession(
                                    id = "scan-auto",
                                    summary = "Automatic probe summary",
                                    startedAt = 10L,
                                    finishedAt = 20L,
                                    launchOrigin = DiagnosticsScanLaunchOrigin.AUTOMATIC_BACKGROUND,
                                    launchTrigger =
                                        DiagnosticsScanLaunchTrigger(
                                            type = DiagnosticsScanTriggerType.POLICY_HANDOVER,
                                            classification = "transport_switch",
                                        ),
                                ),
                            ),
                    ),
            )

        assertNull(uiState.overview.recentAutomaticProbe)
    }
}

private fun diagnosticsUiStateInput(sessions: List<com.poyka.ripdpi.diagnostics.DiagnosticScanSession>) =
    DiagnosticsUiStateInput(
        profiles = emptyList(),
        settings = AppSettingsSerializer.defaultValue,
        progress = null,
        sessions = sessions,
        approachStats = emptyList(),
        snapshots = emptyList(),
        contexts = emptyList(),
        currentTelemetry = null,
        telemetry = emptyList(),
        nativeEvents = emptyList(),
        activeConnectionSession = null,
        liveSnapshots = emptyList(),
        liveContexts = emptyList(),
        liveTelemetry = emptyList(),
        liveNativeEvents = emptyList(),
        exports = emptyList(),
        rememberedPolicies = emptyList(),
        activeConnectionPolicy = null,
        selectedSectionRequest = DiagnosticsSection.Overview,
        selectedProfileId = null,
        selectedApproachMode = DiagnosticsApproachMode.Profiles,
        selectedProbe = null,
        selectedEventId = null,
        sessionPathMode = null,
        sessionStatus = null,
        sessionSearch = "",
        eventSource = null,
        eventSeverity = null,
        eventSearch = "",
        eventAutoScroll = true,
        selectedSessionDetail = null,
        selectedStrategyProbeCandidate = null,
        selectedApproachDetail = null,
        sensitiveSessionDetailsVisible = false,
        archiveActionState = ArchiveActionState(),
        scanStartedAt = null,
        activeScanPathMode = null,
    )
