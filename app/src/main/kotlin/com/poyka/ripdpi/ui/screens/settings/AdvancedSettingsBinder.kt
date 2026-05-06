package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.SettingsEffect
import com.poyka.ripdpi.activities.SettingsMutation
import com.poyka.ripdpi.activities.SettingsNoticeTone
import com.poyka.ripdpi.data.DefaultAppRoutingRussianPresetId
import com.poyka.ripdpi.data.normalizeAppRoutingPolicyMode
import com.poyka.ripdpi.data.normalizeDhtMitigationMode
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
    private val updateSetting: (String, String, SettingsMutation) -> Unit,
) {
    private val writer = AdvancedSettingsMutationWriter(updateSetting)

    fun onToggleChanged(
        setting: AdvancedToggleSetting,
        enabled: Boolean,
    ) {
        toggleHandlers.getValue(setting).invoke(writer, enabled)
    }

    fun onTextConfirmed(
        setting: AdvancedTextSetting,
        value: String,
        uiState: SettingsUiState,
    ) {
        textHandlers.getValue(setting).invoke(writer, value, uiState)
    }

    fun onOptionSelected(
        setting: AdvancedOptionSetting,
        value: String,
        uiState: SettingsUiState,
    ) {
        optionHandlers.getValue(setting).invoke(writer, value, uiState)
    }

    fun onSaveActivationRange(
        dimension: ActivationWindowDimension,
        start: Long?,
        end: Long?,
        uiState: SettingsUiState,
    ) {
        writer.updateActivationRange(dimension, start, end, uiState)
    }

    fun onWsTunnelModeChanged(mode: String) {
        writer.updateValue("wsTunnelMode", mode) {
            setWsTunnelMode(mode)
                .setWsTunnelEnabled(mode != "off")
        }
    }

    fun onResetAdaptiveSplit(uiState: SettingsUiState) {
        writer.updatePrimarySplitMarker(
            uiState = uiState,
            key = "splitMarker",
            marker = manualSplitMarkerFallback(uiState),
        )
    }

    fun onRoutingPolicyModeSelected(value: String) {
        val normalized = normalizeAppRoutingPolicyMode(value)
        writer.updateValue("appRoutingPolicyMode", normalized) {
            setAppRoutingPolicyMode(normalized)
        }
    }

    fun onDhtMitigationModeSelected(value: String) {
        val normalized = normalizeDhtMitigationMode(value)
        writer.updateValue("dhtMitigationMode", normalized) {
            setDhtMitigationMode(normalized)
        }
    }

    fun onAntiCorrelationEnabledChanged(enabled: Boolean) {
        writer.updateBoolean("antiCorrelationEnabled", enabled) {
            setAntiCorrelationEnabled(enabled)
        }
    }

    fun onAppRoutingPresetEnabledChanged(
        presetId: String,
        enabled: Boolean,
        uiState: SettingsUiState,
    ) {
        val updatedPresetIds = uiState.routingProtection.enabledPresetIds.toMutableSet()
        if (enabled) {
            updatedPresetIds += presetId
        } else {
            updatedPresetIds -= presetId
        }
        writer.updateValue("appRoutingEnabledPresetIds", updatedPresetIds.joinToString(",")) {
            clearAppRoutingEnabledPresetIds()
            if (updatedPresetIds.isNotEmpty()) {
                addAllAppRoutingEnabledPresetIds(updatedPresetIds.sorted())
            }
            setExcludeRussianAppsEnabled(DefaultAppRoutingRussianPresetId in updatedPresetIds)
        }
    }
}
