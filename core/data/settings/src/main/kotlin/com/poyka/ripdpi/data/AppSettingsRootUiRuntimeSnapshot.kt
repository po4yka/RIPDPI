package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
internal data class AppSettingsRootUiRuntimeSnapshot(
    val appTheme: String = defaultSettings.appTheme,
    val hapticFeedbackDisabled: Boolean = defaultSettings.hapticFeedbackDisabled,
    val dynamicColorEnabled: Boolean = defaultSettings.dynamicColorEnabled,
    val mode: Mode = Mode.fromString(defaultSettings.ripdpiMode),
    val onboardingComplete: Boolean = defaultSettings.onboardingComplete,
    val webrtcProtectionEnabled: Boolean = defaultSettings.webrtcProtectionEnabled,
    val biometricEnabled: Boolean = defaultSettings.biometricEnabled,
    @Transient
    val backupPin: String = "",
    val appIconVariant: String = defaultSettings.appIconVariant,
    val appIconStyle: String = defaultSettings.appIconStyle,
    val simpleFailoverAwgProfileId: String = defaultSettings.simpleFailoverAwgProfileId,
)
