package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.ui.state.SettingsUiState

internal typealias ToggleHandler = AdvancedSettingsMutationWriters.(Boolean) -> Unit
internal typealias TextHandler = AdvancedSettingsMutationWriters.(String, SettingsUiState) -> Unit
internal typealias OptionHandler = AdvancedSettingsMutationWriters.(String, SettingsUiState) -> Unit

internal typealias CoreToggleHandler = CoreSettingsMutationWriter.(Boolean) -> Unit
internal typealias CoreTextHandler = CoreSettingsMutationWriter.(String, SettingsUiState) -> Unit
internal typealias CoreOptionHandler = CoreSettingsMutationWriter.(String, SettingsUiState) -> Unit

internal typealias DesyncToggleHandler = DesyncSettingsMutationWriter.(Boolean) -> Unit
internal typealias DesyncTextHandler = DesyncSettingsMutationWriter.(String, SettingsUiState) -> Unit
internal typealias DesyncOptionHandler = DesyncSettingsMutationWriter.(String, SettingsUiState) -> Unit

internal typealias ActivationWindowTextHandler =
    ActivationWindowSettingsMutationWriter.(String, SettingsUiState) -> Unit
internal typealias QuicToggleHandler = QuicSettingsMutationWriter.(Boolean) -> Unit
internal typealias QuicTextHandler = QuicSettingsMutationWriter.(String, SettingsUiState) -> Unit
internal typealias QuicOptionHandler = QuicSettingsMutationWriter.(String, SettingsUiState) -> Unit
internal typealias WarpToggleHandler = WarpSettingsMutationWriter.(Boolean) -> Unit
internal typealias WarpTextHandler = WarpSettingsMutationWriter.(String, SettingsUiState) -> Unit
internal typealias WarpOptionHandler = WarpSettingsMutationWriter.(String, SettingsUiState) -> Unit
internal typealias HostAutolearnToggleHandler = HostAutolearnSettingsMutationWriter.(Boolean) -> Unit
internal typealias HostAutolearnTextHandler = HostAutolearnSettingsMutationWriter.(String, SettingsUiState) -> Unit
internal typealias AdaptiveFallbackToggleHandler = AdaptiveFallbackSettingsMutationWriter.(Boolean) -> Unit
internal typealias AdaptiveFallbackTextHandler =
    AdaptiveFallbackSettingsMutationWriter.(String, SettingsUiState) -> Unit

internal val toggleHandlers: Map<AdvancedToggleSetting, ToggleHandler> =
    bindToggleHandlers(coreToggleHandlers) { core } +
        bindToggleHandlers(desyncToggleHandlers) { desync } +
        bindToggleHandlers(quicToggleHandlers) { quic } +
        bindToggleHandlers(warpToggleHandlers) { warp } +
        bindToggleHandlers(hostAutolearnToggleHandlers) { autolearn } +
        bindToggleHandlers(adaptiveFallbackToggleHandlers) { adaptiveFallback }

internal val textHandlers: Map<AdvancedTextSetting, TextHandler> =
    bindTextHandlers(coreTextHandlers) { core } +
        bindTextHandlers(desyncTextHandlers) { desync } +
        bindTextHandlers(activationWindowTextHandlers) { activationWindow } +
        bindTextHandlers(quicTextHandlers) { quic } +
        bindTextHandlers(warpTextHandlers) { warp } +
        bindTextHandlers(hostAutolearnTextHandlers) { autolearn } +
        bindTextHandlers(adaptiveFallbackTextHandlers) { adaptiveFallback }

internal val optionHandlers: Map<AdvancedOptionSetting, OptionHandler> =
    bindOptionHandlers(coreOptionHandlers) { core } +
        bindOptionHandlers(desyncOptionHandlers) { desync } +
        bindOptionHandlers(quicOptionHandlers) { quic } +
        bindOptionHandlers(warpOptionHandlers) { warp }

private fun <Writer> bindToggleHandlers(
    handlers: Map<AdvancedToggleSetting, Writer.(Boolean) -> Unit>,
    writer: AdvancedSettingsMutationWriters.() -> Writer,
): Map<AdvancedToggleSetting, ToggleHandler> =
    handlers.mapValues { (_, handler) ->
        { enabled -> handler.invoke(writer(), enabled) }
    }

private fun <Writer> bindTextHandlers(
    handlers: Map<AdvancedTextSetting, Writer.(String, SettingsUiState) -> Unit>,
    writer: AdvancedSettingsMutationWriters.() -> Writer,
): Map<AdvancedTextSetting, TextHandler> =
    handlers.mapValues { (_, handler) ->
        { value, uiState -> handler.invoke(writer(), value, uiState) }
    }

private fun <Writer> bindOptionHandlers(
    handlers: Map<AdvancedOptionSetting, Writer.(String, SettingsUiState) -> Unit>,
    writer: AdvancedSettingsMutationWriters.() -> Writer,
): Map<AdvancedOptionSetting, OptionHandler> =
    handlers.mapValues { (_, handler) ->
        { value, uiState -> handler.invoke(writer(), value, uiState) }
    }
