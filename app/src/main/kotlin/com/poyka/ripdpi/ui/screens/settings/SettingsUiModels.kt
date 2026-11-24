package com.poyka.ripdpi.ui.screens.settings

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
}

enum class SettingsNoticeTone {
    Info,
    Warning,
    Error,
}
