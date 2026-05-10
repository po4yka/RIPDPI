package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import com.poyka.ripdpi.ui.state.SettingsUiState

internal fun LazyListScope.advancedSettingsPrimarySections(
    uiState: SettingsUiState,
    actions: AdvancedSettingsActions,
    contentState: AdvancedSettingsContentState,
) {
    diagnosticsHistorySection(
        uiState = uiState,
        onToggleChanged = actions.onToggleChanged,
        onTextConfirmed = actions.onTextConfirmed,
        onRotateSalt = actions.onRotateSalt,
    )
    strategyConfigSection(onOpenStrategyConfig = actions.onOpenStrategyConfig)
    commandLineOverridesSection(
        uiState = uiState,
        onToggleChanged = actions.onToggleChanged,
        onTextConfirmed = actions.onTextConfirmed,
    )
    proxySection(uiState, contentState.visualEditorEnabled, actions.onToggleChanged, actions.onTextConfirmed)
    desyncSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        visibility =
            DesyncSectionVisibility(
                showHostFakeSection = contentState.showHostFakeSection,
                showSeqOverlapSection = contentState.showSeqOverlapSection,
                showFakeApproxSection = contentState.showFakeApproxSection,
                showFakeOrderingSection = contentState.showFakeOrderingSection,
                showAdaptiveFakeTtlSection = contentState.showAdaptiveFakeTtlSection,
                showFakePayloadLibrary = contentState.showFakePayloadLibrary,
                showFakeTlsSection = contentState.showFakeTlsSection,
            ),
        options =
            DesyncSectionOptions(
                adaptiveSplitPresetOptions = contentState.adaptiveSplitPresetOptions,
                adaptiveFakeTtlModeOptions = contentState.adaptiveFakeTtlModeOptions,
                httpFakeProfileOptions = contentState.httpFakeProfileOptions,
                fakeTlsBaseOptions = contentState.fakeTlsBaseOptions,
                fakeTlsSniModeOptions = contentState.fakeTlsSniModeOptions,
                tlsFakeProfileOptions = contentState.tlsFakeProfileOptions,
                fakeOrderOptions = contentState.fakeOrderOptions,
                fakeSeqModeOptions = contentState.fakeSeqModeOptions,
                ipIdModeOptions = contentState.ipIdModeOptions,
                udpFakeProfileOptions = contentState.udpFakeProfileOptions,
            ),
        actions =
            DesyncSectionActions(
                onToggleChanged = actions.onToggleChanged,
                onTextConfirmed = actions.onTextConfirmed,
                onOptionSelected = actions.onOptionSelected,
                onResetAdaptiveSplit = actions.onResetAdaptiveSplit,
                onResetAdaptiveFakeTtlProfile = actions.onResetAdaptiveFakeTtlProfile,
                onResetFakePayloadLibrary = actions.onResetFakePayloadLibrary,
                onResetFakeTlsProfile = actions.onResetFakeTlsProfile,
            ),
    )
    activationWindowSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        onResetActivationWindow = actions.onResetActivationWindow,
        onSaveActivationRange = actions.onSaveActivationRange,
    )
    protocolsSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        onToggleChanged = actions.onToggleChanged,
    )
}
