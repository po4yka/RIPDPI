package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.ui.state.SettingsUiState

internal typealias ToggleHandler = AdvancedSettingsMutationWriter.(Boolean) -> Unit
internal typealias TextHandler = AdvancedSettingsMutationWriter.(String, SettingsUiState) -> Unit
internal typealias OptionHandler = AdvancedSettingsMutationWriter.(String, SettingsUiState) -> Unit

internal val toggleHandlers: Map<AdvancedToggleSetting, ToggleHandler> =
    coreToggleHandlers + desyncToggleHandlers + warpToggleHandlers

internal val textHandlers: Map<AdvancedTextSetting, TextHandler> =
    coreTextHandlers + desyncTextHandlers + warpTextHandlers

internal val optionHandlers: Map<AdvancedOptionSetting, OptionHandler> =
    coreOptionHandlers + desyncOptionHandlers + warpOptionHandlers
