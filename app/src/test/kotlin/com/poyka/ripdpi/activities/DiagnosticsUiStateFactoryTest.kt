package com.poyka.ripdpi.activities

import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanLaunchOrigin
import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanLaunchTrigger
import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanTriggerType
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
            resolver = DiagnosticsUiInputResolver(support),
            overviewFactory = DiagnosticsOverviewUiStateFactory(support),
            scanFactory = DiagnosticsScanUiStateFactory(support),
            liveFactory = DiagnosticsLiveUiStateFactory(support),
            sessionsFactory = DiagnosticsSessionsUiStateFactory(support),
            approachesFactory = DiagnosticsApproachesUiStateFactory(support),
            eventsFactory = DiagnosticsEventsUiStateFactory(support),
            shareFactory = DiagnosticsShareUiStateFactory(support),
            performanceFactory = DiagnosticsPerformanceUiStateFactory(),
        )

    @Test
    fun `bundled default profile uses localized title and custom names stay intact`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bundled = DiagnosticProfile("default", "Default diagnostics", "bundled", 1, updatedAt = 0L)
        assertEquals(
            context.getString(com.poyka.ripdpi.R.string.diagnostics_profile_connectivity_title),
            support.toProfileOptionUiModel(bundled).name,
        )
        assertEquals(
            "My profile",
            support.toProfileOptionUiModel(bundled.copy(name = "My profile", source = "user")).name,
        )
        assertEquals(
            "My renamed bundled profile",
            support.toProfileOptionUiModel(bundled.copy(name = "My renamed bundled profile")).name,
        )
        assertEquals(
            "Other profile",
            support.toProfileOptionUiModel(bundled.copy(id = "other", name = "Other profile")).name,
        )
    }

    @Test
    fun `saved persona controls diagnostics disclosure after projection`() {
        val input = diagnosticsUiStateInput(emptyList())
        val guided =
            factory.buildUiState(
                input.copy(
                    settings =
                        input.settings
                            .toBuilder()
                            .setUiPersona("simple")
                            .build(),
                ),
            )
        val advanced =
            factory.buildUiState(
                input.copy(
                    settings =
                        input.settings
                            .toBuilder()
                            .setUiPersona("advanced")
                            .build(),
                ),
            )
        assertEquals("simple", guided.toScreenUiState().uiPersona)
        assertEquals("advanced", advanced.toScreenUiState().uiPersona)
    }

    @Test
    fun `scan has no results from another profile while overview retains the latest session`() {
        val latest = historyScanSession().copy(profileId = "profile-a")
        val input =
            diagnosticsUiStateInput(listOf(latest)).copy(
                profiles =
                    listOf(
                        DiagnosticProfile("profile-a", "Profile A", "bundled", 1, updatedAt = 0L),
                        DiagnosticProfile("profile-b", "Profile B", "bundled", 1, updatedAt = 0L),
                    ),
                selectedProfileId = "profile-b",
            )

        val state = factory.buildUiState(input)

        assertEquals(latest.id, state.overview.latestSession?.id)
        assertNull(state.scan.latestSession)
        assertEquals(emptyList<DiagnosticsProbeResultUiModel>(), state.scan.latestResults)
        assertNull(state.scan.resolverRecommendation)
        assertNull(state.scan.strategyProbeReport)
    }

    @Test
    fun `scan without an available profile does not borrow history results`() {
        val state = factory.buildUiState(diagnosticsUiStateInput(listOf(historyScanSession())))

        assertNotNull(state.overview.latestSession)
        assertNull(state.scan.latestSession)
        assertEquals(emptyList<DiagnosticsProbeResultUiModel>(), state.scan.latestResults)
    }

    @Test
    fun `scan selects matching history even when a different profile is newer`() {
        val matching = historyScanSession(id = "matching").copy(profileId = "profile-b")
        val latest = historyScanSession(id = "newer").copy(profileId = "profile-a")
        val state =
            factory.buildUiState(
                diagnosticsUiStateInput(listOf(latest, matching)).copy(
                    profiles = listOf(DiagnosticProfile("profile-b", "Profile B", "bundled", 1, updatedAt = 0L)),
                    selectedProfileId = "profile-b",
                ),
            )

        assertEquals(matching.id, state.scan.latestSession?.id)
        assertEquals(1, state.scan.latestResults.size)
        assertEquals(latest.id, state.overview.latestSession?.id)
    }

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
        serviceStatus = com.poyka.ripdpi.data.AppStatus.Halted,
        selectedSectionRequest = DiagnosticsSection.Dashboard,
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
