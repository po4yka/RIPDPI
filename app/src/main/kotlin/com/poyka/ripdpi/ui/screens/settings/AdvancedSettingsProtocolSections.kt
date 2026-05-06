package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import com.poyka.ripdpi.activities.HostPackCatalogUiState
import com.poyka.ripdpi.activities.StrategyPackCatalogUiState
import com.poyka.ripdpi.data.HostPackPreset
import com.poyka.ripdpi.ui.state.SettingsUiState

internal fun LazyListScope.advancedSettingsProtocolSections(
    uiState: SettingsUiState,
    hostPackCatalog: HostPackCatalogUiState,
    strategyPackCatalog: StrategyPackCatalogUiState,
    actions: AdvancedSettingsActions,
    contentState: AdvancedSettingsContentState,
    pendingHostPack: HostPackPreset?,
    onPresetSelected: (HostPackPreset) -> Unit,
) {
    httpParserSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        onToggleChanged = actions.onToggleChanged,
        onResetHttpParserEvasions = actions.onResetHttpParserEvasions,
    )
    httpsSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        onTextConfirmed = actions.onTextConfirmed,
        onOptionSelected = actions.onOptionSelected,
    )
    udpSection()
    quicSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        showQuicFakeSection = contentState.showQuicFakeSection,
        quicModeOptions = contentState.quicModeOptions,
        onToggleChanged = actions.onToggleChanged,
        onOptionSelected = actions.onOptionSelected,
        onTextConfirmed = actions.onTextConfirmed,
    )
    detectionResistanceSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        tlsFingerprintOptions = contentState.tlsFingerprintOptions,
        entropyModeOptions = contentState.entropyModeOptions,
        onToggleChanged = actions.onToggleChanged,
        onOptionSelected = actions.onOptionSelected,
        onTextConfirmed = actions.onTextConfirmed,
    )
    warpSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        routeModeOptions = contentState.warpRouteModeOptions,
        endpointSelectionOptions = contentState.warpEndpointSelectionOptions,
        amneziaPresetOptions = contentState.warpAmneziaPresetOptions,
        onToggleChanged = actions.onToggleChanged,
        onOptionSelected = actions.onOptionSelected,
        onTextConfirmed = actions.onTextConfirmed,
    )
    hostsSection(
        uiState = uiState,
        hostPackCatalog = hostPackCatalog,
        visualEditorEnabled = contentState.visualEditorEnabled,
        hostPackApplyControlsEnabled = contentState.hostPackApplyControlsEnabled,
        hostsOptions = contentState.hostsOptions,
        pendingHostPack = pendingHostPack,
        onPresetSelected = onPresetSelected,
        onRefreshHostPackCatalog = actions.onRefreshHostPackCatalog,
        onOptionSelected = actions.onOptionSelected,
        onTextConfirmed = actions.onTextConfirmed,
    )
    strategyPackSection(
        strategyPackCatalog = strategyPackCatalog,
        onRefreshStrategyPackCatalog = actions.onRefreshStrategyPackCatalog,
    )
}
