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
}

enum class SettingsNoticeTone {
    Info,
    Warning,
    Error,
}
