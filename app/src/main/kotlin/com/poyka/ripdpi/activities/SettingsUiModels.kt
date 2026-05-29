package com.poyka.ripdpi.activities

import com.poyka.ripdpi.proto.AppSettings

internal typealias SettingsMutation = AppSettings.Builder.() -> Unit

sealed interface SettingsEffect {
    data class SettingChanged(
        val key: String,
        val value: String,
    ) : SettingsEffect

    data class Notice(
        val title: String,
        val message: String,
        val tone: SettingsNoticeTone,
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
