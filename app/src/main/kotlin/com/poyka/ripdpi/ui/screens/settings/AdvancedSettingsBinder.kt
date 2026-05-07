package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.SettingsEffect
import com.poyka.ripdpi.activities.SettingsMutation
import com.poyka.ripdpi.activities.SettingsNoticeTone
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.state.SettingsUiState

internal fun mapNoticeEffect(effect: SettingsEffect.Notice): AdvancedNotice =
    AdvancedNotice(
        title = effect.title,
        message = effect.message,
        tone =
            when (effect.tone) {
                SettingsNoticeTone.Info -> WarningBannerTone.Info
                SettingsNoticeTone.Warning -> WarningBannerTone.Warning
                SettingsNoticeTone.Error -> WarningBannerTone.Error
            },
    )

internal class AdvancedSettingsBinder(
    updateSetting: (String, String, SettingsMutation) -> Unit,
) {
    private val writers = AdvancedSettingsMutationWriters(updateSetting)

    fun onToggleChanged(
        setting: AdvancedToggleSetting,
        enabled: Boolean,
    ) {
        toggleHandlers.getValue(setting).invoke(writers, enabled)
    }

    fun onTextConfirmed(
        setting: AdvancedTextSetting,
        value: String,
        uiState: SettingsUiState,
    ) {
        textHandlers.getValue(setting).invoke(writers, value, uiState)
    }

    fun onOptionSelected(
        setting: AdvancedOptionSetting,
        value: String,
        uiState: SettingsUiState,
    ) {
        optionHandlers.getValue(setting).invoke(writers, value, uiState)
    }

    fun onSaveActivationRange(
        dimension: ActivationWindowDimension,
        start: Long?,
        end: Long?,
        uiState: SettingsUiState,
    ) {
        writers.activationWindow.updateActivationRange(dimension, start, end, uiState)
    }

    fun onWsTunnelModeChanged(mode: String) {
        writers.core.updateWsTunnelMode(mode)
    }

    fun onResetAdaptiveSplit(uiState: SettingsUiState) {
        writers.desync.updatePrimarySplitMarker(
            uiState = uiState,
            key = "splitMarker",
            marker = manualSplitMarkerFallback(uiState),
        )
    }

    fun onRoutingPolicyModeSelected(value: String) {
        writers.core.updateRoutingPolicyMode(value)
    }

    fun onDhtMitigationModeSelected(value: String) {
        writers.core.updateDhtMitigationMode(value)
    }

    fun onAntiCorrelationEnabledChanged(enabled: Boolean) {
        writers.core.updateAntiCorrelationEnabled(enabled)
    }

    fun onAppRoutingPresetEnabledChanged(
        presetId: String,
        enabled: Boolean,
        uiState: SettingsUiState,
    ) {
        writers.core.updateAppRoutingPresetEnabled(presetId, enabled, uiState)
    }
}
