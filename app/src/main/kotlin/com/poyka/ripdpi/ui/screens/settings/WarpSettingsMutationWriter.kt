package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.SettingsMutation
import com.poyka.ripdpi.data.WarpAmneziaPresetCustom
import com.poyka.ripdpi.data.WarpAmneziaPresetOff
import com.poyka.ripdpi.data.WarpAmneziaSettings
import com.poyka.ripdpi.data.normalizeWarpAmneziaPreset
import com.poyka.ripdpi.data.normalizeWarpEndpointSelectionMode
import com.poyka.ripdpi.data.normalizeWarpRouteMode
import com.poyka.ripdpi.data.resolveWarpAmneziaProfile
import com.poyka.ripdpi.ui.state.SettingsUiState

internal const val MaxWarpEndpointPort = 65535

internal class WarpSettingsMutationWriter(
    update: (String, String, SettingsMutation) -> Unit,
) : AdvancedSettingsMutationWriter(update) {
    fun updateWarpRouteMode(value: String) {
        val normalized = normalizeWarpRouteMode(value)
        updateValue("warpRouteMode", normalized) {
            setWarpRouteMode(normalized)
        }
    }

    fun updateWarpEndpointSelectionMode(value: String) {
        val normalized = normalizeWarpEndpointSelectionMode(value)
        updateValue("warpEndpointSelectionMode", normalized) {
            setWarpEndpointSelectionMode(normalized)
        }
    }

    fun updateWarpAmneziaPreset(
        value: String,
        uiState: SettingsUiState,
    ) {
        val normalized = normalizeWarpAmneziaPreset(value)
        val rawSettings =
            WarpAmneziaSettings(
                enabled = uiState.warp.amneziaEnabled,
                jc = uiState.warp.amneziaJc,
                jmin = uiState.warp.amneziaJmin,
                jmax = uiState.warp.amneziaJmax,
                h1 = uiState.warp.amneziaH1,
                h2 = uiState.warp.amneziaH2,
                h3 = uiState.warp.amneziaH3,
                h4 = uiState.warp.amneziaH4,
                s1 = uiState.warp.amneziaS1,
                s2 = uiState.warp.amneziaS2,
                s3 = uiState.warp.amneziaS3,
                s4 = uiState.warp.amneziaS4,
            )
        val resolved =
            resolveWarpAmneziaProfile(
                preset = normalized,
                rawSettings =
                    if (normalized == WarpAmneziaPresetCustom) {
                        rawSettings.copy(enabled = true)
                    } else {
                        rawSettings
                    },
            ).settings

        updateValue("warpAmneziaPreset", normalized) {
            setWarpAmneziaPreset(normalized)
            setWarpAmneziaEnabled(normalized != WarpAmneziaPresetOff)
            setWarpAmneziaJc(resolved.jc)
            setWarpAmneziaJmin(resolved.jmin)
            setWarpAmneziaJmax(resolved.jmax)
            setWarpAmneziaH1(resolved.h1)
            setWarpAmneziaH2(resolved.h2)
            setWarpAmneziaH3(resolved.h3)
            setWarpAmneziaH4(resolved.h4)
            setWarpAmneziaS1(resolved.s1)
            setWarpAmneziaS2(resolved.s2)
            setWarpAmneziaS3(resolved.s3)
            setWarpAmneziaS4(resolved.s4)
        }
    }
}
