package com.poyka.ripdpi.ui.screens.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.poyka.ripdpi.activities.DiagnosticsSessionFiltersUiModel
import com.poyka.ripdpi.activities.DiagnosticsSessionRowUiModel
import com.poyka.ripdpi.activities.DiagnosticsSessionsUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.HistoryConnectionRowUiModel
import com.poyka.ripdpi.activities.HistoryConnectionsUiModel
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.activities.HistoryUiState
import com.poyka.ripdpi.diagnostics.DiagnosticsScanLaunchOrigin
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HistoryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun historyDiagnosticsCardShowsAutomaticProbeBadge() {
        composeRule.setContent {
            RipDpiTheme {
                HistoryScreen(
                    uiState =
                        HistoryUiState(
                            selectedSection = HistorySection.Diagnostics,
                            diagnostics =
                                DiagnosticsSessionsUiModel(
                                    filters = DiagnosticsSessionFiltersUiModel(),
                                    sessions =
                                        listOf(
                                            DiagnosticsSessionRowUiModel(
                                                id = "scan-auto",
                                                profileId = "automatic-probing",
                                                title = "Automatic probe summary",
                                                subtitle = "RAW_PATH · VPN · Mar 27",
                                                pathMode = "RAW_PATH",
                                                serviceMode = "VPN",
                                                status = "completed",
                                                startedAtLabel = "Mar 27",
                                                summary = "Automatic probe summary",
                                                metrics = emptyList(),
                                                tone = DiagnosticsTone.Positive,
                                                launchOrigin = DiagnosticsScanLaunchOrigin.AUTOMATIC_BACKGROUND,
                                                triggerClassification = "transport_switch",
                                            ),
                                        ),
                                    pathModes = listOf("RAW_PATH"),
                                    statuses = listOf("completed"),
                                ),
                        ),
                    onBack = {},
                    onSelectSection = {},
                    onConnectionModeFilter = {},
                    onConnectionStatusFilter = {},
                    onConnectionSearch = {},
                    onClearConnectionFilters = {},
                    onDiagnosticsPathFilter = {},
                    onDiagnosticsStatusFilter = {},
                    onDiagnosticsSearch = {},
                    onClearDiagnosticsFilters = {},
                    onToggleEventFilter = { _, _ -> },
                    onEventSearch = {},
                    onClearEventFilters = {},
                    onEventAutoScroll = {},
                    onSelectConnection = {},
                    onDismissConnectionDetail = {},
                    onSelectDiagnosticsSession = {},
                    onDismissDiagnosticsDetail = {},
                    onSelectEvent = {},
                    onDismissEventDetail = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(
                RipDpiTestTags.historyDiagnosticsAutomaticBadge("scan-auto"),
                useUnmergedTree = true,
            ).assertIsDisplayed()
    }

    @Test
    fun historyConnectionCardShowsRememberedPolicyBadge() {
        composeRule.setContent {
            RipDpiTheme {
                HistoryScreen(
                    uiState =
                        HistoryUiState(
                            selectedSection = HistorySection.Connections,
                            connections =
                                HistoryConnectionsUiModel(
                                    sessions =
                                        listOf(
                                            HistoryConnectionRowUiModel(
                                                id = "connection-remembered",
                                                title = "VPN running",
                                                subtitle = "wifi · Mar 27",
                                                serviceMode = "VPN",
                                                connectionState = "Running",
                                                networkType = "wifi",
                                                startedAtLabel = "Mar 27",
                                                summary = "Remembered policy reused",
                                                rememberedPolicyBadge = "Remembered policy",
                                                metrics = emptyList(),
                                                tone = DiagnosticsTone.Positive,
                                            ),
                                        ),
                                    modes = listOf("VPN"),
                                    statuses = listOf("Running"),
                                ),
                        ),
                    onBack = {},
                    onSelectSection = {},
                    onConnectionModeFilter = {},
                    onConnectionStatusFilter = {},
                    onConnectionSearch = {},
                    onClearConnectionFilters = {},
                    onDiagnosticsPathFilter = {},
                    onDiagnosticsStatusFilter = {},
                    onDiagnosticsSearch = {},
                    onClearDiagnosticsFilters = {},
                    onToggleEventFilter = { _, _ -> },
                    onEventSearch = {},
                    onClearEventFilters = {},
                    onEventAutoScroll = {},
                    onSelectConnection = {},
                    onDismissConnectionDetail = {},
                    onSelectDiagnosticsSession = {},
                    onDismissDiagnosticsDetail = {},
                    onSelectEvent = {},
                    onDismissEventDetail = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(
                RipDpiTestTags.historyConnectionRememberedBadge("connection-remembered"),
                useUnmergedTree = true,
            ).assertIsDisplayed()
    }
}
