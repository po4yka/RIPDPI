package com.poyka.ripdpi.ui.screens.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.activities.HistoryUiState
import com.poyka.ripdpi.ui.theme.RipDpiTheme

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
