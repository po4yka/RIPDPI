package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.SettingsMutation
import com.poyka.ripdpi.data.DefaultAppRoutingRussianPresetId
import com.poyka.ripdpi.data.normalizeAppRoutingPolicyMode
import com.poyka.ripdpi.data.normalizeDhtMitigationMode
import com.poyka.ripdpi.ui.state.SettingsUiState

internal class CoreSettingsMutationWriter(
    update: (String, String, SettingsMutation) -> Unit,
) : AdvancedSettingsMutationWriter(update) {
    fun updateWsTunnelMode(mode: String) {
        updateValue("wsTunnelMode", mode) {
            setWsTunnelMode(mode)
                .setWsTunnelEnabled(mode != "off")
        }
    }

    fun updateRoutingPolicyMode(value: String) {
        val normalized = normalizeAppRoutingPolicyMode(value)
        updateValue("appRoutingPolicyMode", normalized) {
            setAppRoutingPolicyMode(normalized)
        }
    }

    fun updateDhtMitigationMode(value: String) {
        val normalized = normalizeDhtMitigationMode(value)
        updateValue("dhtMitigationMode", normalized) {
            setDhtMitigationMode(normalized)
        }
    }

    fun updateAntiCorrelationEnabled(enabled: Boolean) {
        updateBoolean("antiCorrelationEnabled", enabled) {
            setAntiCorrelationEnabled(enabled)
        }
    }

    fun updateAppRoutingPresetEnabled(
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
        updateValue("appRoutingEnabledPresetIds", updatedPresetIds.joinToString(",")) {
            clearAppRoutingEnabledPresetIds()
            if (updatedPresetIds.isNotEmpty()) {
                addAllAppRoutingEnabledPresetIds(updatedPresetIds.sorted())
            }
            setExcludeRussianAppsEnabled(DefaultAppRoutingRussianPresetId in updatedPresetIds)
        }
    }
}
