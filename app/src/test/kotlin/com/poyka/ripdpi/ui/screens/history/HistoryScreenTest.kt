package com.poyka.ripdpi.ui.screens.history

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.activities.DiagnosticsSessionDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsSessionFiltersUiModel
import com.poyka.ripdpi.activities.DiagnosticsSessionRowUiModel
import com.poyka.ripdpi.activities.DiagnosticsSessionsUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.HistoryConnectionRowUiModel
import com.poyka.ripdpi.activities.HistoryConnectionsUiModel
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.activities.HistoryUiState
import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanLaunchOrigin
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
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
        val session =
            DiagnosticsSessionRowUiModel(
                id = "scan-auto",
                profileId = "automatic-probing",
                title = "Automatic probe summary",
                subtitle = "RAW_PATH · VPN · Mar 27",
                pathMode = "RAW_PATH",
                serviceMode = "VPN",
                status = "completed",
                completionLabel = "Частичные результаты",
                startedAtLabel = "Mar 27",
                summary = "Automatic probe summary",
                metrics = persistentListOf(),
                tone = DiagnosticsTone.Warning,
                launchOrigin = DiagnosticsScanLaunchOrigin.AUTOMATIC_BACKGROUND,
                triggerClassification = "transport_switch",
            )
        composeRule.setContent {
            RipDpiTheme {
                HistoryScreen(
                    uiState =
                        HistoryUiState(
                            selectedSection = HistorySection.Diagnostics,
                            diagnostics =
                                DiagnosticsSessionsUiModel(
                                    filters = DiagnosticsSessionFiltersUiModel(),
                                    sessions = persistentListOf(session),
                                    pathModes = persistentListOf("RAW_PATH"),
                                    statuses = persistentListOf("completed"),
                                ),
                            selectedDiagnosticsDetail =
                                DiagnosticsSessionDetailUiModel(
                                    session = session,
                                    probeGroups = persistentListOf(),
                                    snapshots = persistentListOf(),
                                    events = persistentListOf(),
                                    contextGroups = persistentListOf(),
                                    hasSensitiveDetails = false,
                                    sensitiveDetailsVisible = false,
                                ),
                        ),
                    onBack = {},
                    onRefresh = {},
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
        composeRule.onAllNodesWithText("Частичные результаты").assertCountEquals(2)
    }

    @Test
    fun historyPathFiltersDisplayLocalizedScopeAndPreserveMachineSelection() {
        var selectedPath: String? = null
        composeRule.setContent {
            RipDpiTheme {
                FilterCard(
                    title = "Diagnostics",
                    searchValue = "",
                    searchPlaceholder = "Search",
                    onSearch = {},
                    searchTestTag = RipDpiTestTags.HistoryDiagnosticsSearch,
                    primaryFilter =
                        HistoryFilterChipsConfig(
                            options = listOf("RAW_PATH", "IN_PATH"),
                            selected = null,
                            onSelect = { selectedPath = it },
                            tagForOption = { RipDpiTestTags.historyDiagnosticsPathFilter(it) },
                        ),
                    secondaryFilter =
                        HistoryFilterChipsConfig(
                            options = emptyList(),
                            selected = null,
                            onSelect = {},
                            tagForOption = { it },
                        ),
                    onClearFilters = {},
                )
            }
        }
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        listOf(
            "RAW_PATH" to com.poyka.ripdpi.R.string.diagnostics_scope_direct_label,
            "IN_PATH" to com.poyka.ripdpi.R.string.diagnostics_scope_active_label,
        ).forEach { (machineValue, labelResource) ->
            composeRule
                .onNodeWithTag(RipDpiTestTags.historyDiagnosticsPathFilter(machineValue))
                .assertTextEquals(context.getString(labelResource))
                .performClick()
            org.junit.Assert.assertEquals(machineValue, selectedPath)
        }
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
                                        persistentListOf(
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
                                                metrics = persistentListOf(),
                                                tone = DiagnosticsTone.Positive,
                                            ),
                                        ),
                                    modes = persistentListOf("VPN"),
                                    statuses = persistentListOf("Running"),
                                ),
                        ),
                    onBack = {},
                    onRefresh = {},
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
