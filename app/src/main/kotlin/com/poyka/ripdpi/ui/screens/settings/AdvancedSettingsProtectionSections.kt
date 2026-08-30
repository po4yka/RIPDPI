package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import com.poyka.ripdpi.ui.state.SettingsUiState

internal fun LazyListScope.advancedSettingsProtectionSections(
    uiState: SettingsUiState,
    actions: AdvancedSettingsActions,
    contentState: AdvancedSettingsContentState,
) {
    hostAutolearnSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        onToggleChanged = actions.onToggleChanged,
        onTextConfirmed = actions.onTextConfirmed,
        onForgetLearnedHosts = actions.onForgetLearnedHosts,
    )
    networkStrategyMemorySection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        onToggleChanged = actions.onToggleChanged,
        onClearRememberedNetworks = actions.onClearRememberedNetworks,
        onOpenRememberedNetworks = actions.onOpenRememberedNetworks,
    )
    wsTunnelSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        onWsTunnelModeChanged = actions.onWsTunnelModeChanged,
        onToggleChanged = actions.onToggleChanged,
        onSaveWorkerTransport = actions.onSaveWsTunnelWorkerTransport,
        onClearWorkerTransport = actions.onClearWsTunnelWorkerTransport,
    )
    adaptiveFallbackSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        onToggleChanged = actions.onToggleChanged,
        onTextConfirmed = actions.onTextConfirmed,
    )
    routingProtectionSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        onRoutingPolicyModeSelected = actions.onRoutingPolicyModeSelected,
        onDhtMitigationModeSelected = actions.onDhtMitigationModeSelected,
        onAntiCorrelationEnabledChanged = actions.onAntiCorrelationEnabledChanged,
        onAppRoutingPresetEnabledChanged = actions.onAppRoutingPresetEnabledChanged,
    )
}
