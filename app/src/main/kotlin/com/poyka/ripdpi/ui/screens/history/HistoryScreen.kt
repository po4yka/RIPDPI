package com.poyka.ripdpi.ui.screens.history

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.activities.HistoryUiState
import com.poyka.ripdpi.activities.HistoryViewModel
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun HistoryRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel) {
        viewModel.initialize()
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HistoryScreen(
        uiState = uiState,
        onBack = onBack,
        onRefresh = remember(viewModel) { viewModel::refresh },
        onSelectSection = remember(viewModel) { viewModel::selectSection },
        onConnectionModeFilter = remember(viewModel) { viewModel::setConnectionModeFilter },
        onConnectionStatusFilter = remember(viewModel) { viewModel::setConnectionStatusFilter },
        onConnectionSearch = remember(viewModel) { viewModel::setConnectionSearch },
        onClearConnectionFilters = remember(viewModel) { viewModel::clearConnectionFilters },
        onSelectConnection = remember(viewModel) { viewModel::selectConnection },
        onDismissConnectionDetail = remember(viewModel) { viewModel::dismissConnectionDetail },
        onDiagnosticsPathFilter = remember(viewModel) { viewModel::setDiagnosticsPathModeFilter },
        onDiagnosticsStatusFilter = remember(viewModel) { viewModel::setDiagnosticsStatusFilter },
        onDiagnosticsSearch = remember(viewModel) { viewModel::setDiagnosticsSearch },
        onClearDiagnosticsFilters = remember(viewModel) { viewModel::clearDiagnosticsFilters },
        onSelectDiagnosticsSession = remember(viewModel) { viewModel::selectDiagnosticsSession },
        onDismissDiagnosticsDetail = remember(viewModel) { viewModel::dismissDiagnosticsDetail },
        onToggleEventFilter = remember(viewModel) { viewModel::toggleEventFilter },
        onEventSearch = remember(viewModel) { viewModel::setEventSearch },
        onClearEventFilters = remember(viewModel) { viewModel::clearEventFilters },
        onEventAutoScroll = remember(viewModel) { viewModel::setEventAutoScroll },
        onSelectEvent = remember(viewModel) { viewModel::selectEvent },
        onDismissEventDetail = remember(viewModel) { viewModel::dismissEventDetail },
        modifier = modifier,
    )
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryScreen(
    uiState: HistoryUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectSection: (HistorySection) -> Unit,
    onConnectionModeFilter: (String?) -> Unit,
    onConnectionStatusFilter: (String?) -> Unit,
    onConnectionSearch: (String) -> Unit,
    onClearConnectionFilters: () -> Unit,
    onSelectConnection: (String) -> Unit,
    onDismissConnectionDetail: () -> Unit,
    onDiagnosticsPathFilter: (String?) -> Unit,
    onDiagnosticsStatusFilter: (String?) -> Unit,
    onDiagnosticsSearch: (String) -> Unit,
    onClearDiagnosticsFilters: () -> Unit,
    onSelectDiagnosticsSession: (String) -> Unit,
    onDismissDiagnosticsDetail: () -> Unit,
    onToggleEventFilter: (String?, String?) -> Unit,
    onEventSearch: (String) -> Unit,
    onClearEventFilters: () -> Unit,
    onEventAutoScroll: (Boolean) -> Unit,
    onSelectEvent: (String) -> Unit,
    onDismissEventDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing

    RipDpiScreenScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.History))
                .fillMaxSize()
                .background(colors.background),
        topBar = {
            RipDpiTopAppBar(
                title = stringResource(R.string.history_title),
                navigationIcon = RipDpiIcons.Back,
                onNavigationClick = onBack,
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .padding(innerPadding),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier =
                        Modifier
                            .widthIn(max = layout.contentMaxWidth)
                            .fillMaxSize(),
                ) {
                    HistorySectionChips(
                        selectedSection = uiState.selectedSection,
                        onSelectSection = onSelectSection,
                    )
                    val motion = RipDpiThemeTokens.motion
                    AnimatedContent(
                        targetState = uiState.selectedSection,
                        transitionSpec = {
                            val direction = targetState.ordinal.compareTo(initialState.ordinal)
                            val enterSlide =
                                slideInHorizontally(
                                    initialOffsetX = { fullWidth -> fullWidth / 4 * if (direction >= 0) 1 else -1 },
                                    animationSpec = motion.stateTween(easing = RipDpiMotion.EmphasizedDecelerate),
                                ) +
                                    fadeIn(
                                        animationSpec = motion.stateTween(),
                                    )
                            val exitSlide =
                                slideOutHorizontally(
                                    targetOffsetX = { fullWidth -> fullWidth / 4 * if (direction >= 0) -1 else 1 },
                                    animationSpec = motion.quickTween(easing = RipDpiMotion.EmphasizedAccelerate),
                                ) +
                                    fadeOut(
                                        animationSpec = motion.quickTween(),
                                    )
                            enterSlide togetherWith exitSlide
                        },
                        label = "historySectionContent",
                    ) { section ->
                        when (section) {
                            HistorySection.Connections -> {
                                ConnectionsSection(
                                    uiState = uiState,
                                    onModeFilter = onConnectionModeFilter,
                                    onStatusFilter = onConnectionStatusFilter,
                                    onSearch = onConnectionSearch,
                                    onClearFilters = onClearConnectionFilters,
                                    onSelectConnection = onSelectConnection,
                                )
                            }

                            HistorySection.Diagnostics -> {
                                DiagnosticsSection(
                                    uiState = uiState,
                                    onPathFilter = onDiagnosticsPathFilter,
                                    onStatusFilter = onDiagnosticsStatusFilter,
                                    onSearch = onDiagnosticsSearch,
                                    onClearFilters = onClearDiagnosticsFilters,
                                    onSelectSession = onSelectDiagnosticsSession,
                                )
                            }

                            HistorySection.Events -> {
                                EventsSection(
                                    uiState = uiState,
                                    onToggleFilter = onToggleEventFilter,
                                    onSearch = onEventSearch,
                                    onClearFilters = onClearEventFilters,
                                    onAutoScroll = onEventAutoScroll,
                                    onSelectEvent = onSelectEvent,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    uiState.selectedConnectionDetail?.let { detail ->
        ConnectionDetailSheet(
            detail = detail,
            onDismissRequest = onDismissConnectionDetail,
        )
    }

    uiState.selectedDiagnosticsDetail?.let { detail ->
        DiagnosticsDetailSheet(
            detail = detail,
            onDismissRequest = onDismissDiagnosticsDetail,
        )
    }

    uiState.selectedEvent?.let { event ->
        EventDetailSheet(
            event = event,
            onDismissRequest = onDismissEventDetail,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HistoryScreenConnectionsPreview() {
    RipDpiTheme {
        HistoryScreen(
            uiState = HistoryUiState(selectedSection = HistorySection.Connections),
            onBack = {},
            onRefresh = {},
            onSelectSection = {},
            onConnectionModeFilter = {},
            onConnectionStatusFilter = {},
            onConnectionSearch = {},
            onClearConnectionFilters = {},
            onSelectConnection = {},
            onDismissConnectionDetail = {},
            onDiagnosticsPathFilter = {},
            onDiagnosticsStatusFilter = {},
            onDiagnosticsSearch = {},
            onClearDiagnosticsFilters = {},
            onSelectDiagnosticsSession = {},
            onDismissDiagnosticsDetail = {},
            onToggleEventFilter = { _, _ -> },
            onEventSearch = {},
            onClearEventFilters = {},
            onEventAutoScroll = {},
            onSelectEvent = {},
            onDismissEventDetail = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HistoryScreenEventsPreview() {
    RipDpiTheme {
        HistoryScreen(
            uiState = HistoryUiState(selectedSection = HistorySection.Events),
            onBack = {},
            onRefresh = {},
            onSelectSection = {},
            onConnectionModeFilter = {},
            onConnectionStatusFilter = {},
            onConnectionSearch = {},
            onClearConnectionFilters = {},
            onSelectConnection = {},
            onDismissConnectionDetail = {},
            onDiagnosticsPathFilter = {},
            onDiagnosticsStatusFilter = {},
            onDiagnosticsSearch = {},
            onClearDiagnosticsFilters = {},
            onSelectDiagnosticsSession = {},
            onDismissDiagnosticsDetail = {},
            onToggleEventFilter = { _, _ -> },
            onEventSearch = {},
            onClearEventFilters = {},
            onEventAutoScroll = {},
            onSelectEvent = {},
            onDismissEventDetail = {},
        )
    }
}
