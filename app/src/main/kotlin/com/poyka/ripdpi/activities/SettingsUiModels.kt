package com.poyka.ripdpi.activities

import androidx.annotation.StringRes
import com.poyka.ripdpi.proto.AppSettings

internal typealias SettingsMutation = AppSettings.Builder.() -> Unit

sealed interface SettingsEffect {
    data class SettingChanged(
        val key: String,
        val value: String,
    ) : SettingsEffect

    data class Notice(
        @param:StringRes val title: Int,
        @param:StringRes val message: Int,
        val tone: SettingsNoticeTone,
        val messageOverride: String? = null,
    ) : SettingsEffect

    /**
     * Requests the host screen to launch the battery-optimization / vendor-autostart
     * exemption flow after the user enables "Start on boot". The screen resolves the
     * concrete intents from [com.poyka.ripdpi.platform.StartOnBootController] — Intent
     * objects are intentionally kept out of the effect so it stays cleanly equatable.
     */
    data object RequestDisableBatteryOptimization : SettingsEffect
}

enum class SettingsNoticeTone {
    Info,
    Warning,
    Error,
}
