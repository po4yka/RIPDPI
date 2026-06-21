package com.poyka.ripdpi.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

internal data class AppSettingsRootUiRuntimeSnapshot(
    val appTheme: String = defaultSettings.appTheme,
    val mode: Mode = Mode.fromString(defaultSettings.ripdpiMode),
    val onboardingComplete: Boolean = defaultSettings.onboardingComplete,
    val webrtcProtectionEnabled: Boolean = defaultSettings.webrtcProtectionEnabled,
    val biometricEnabled: Boolean = defaultSettings.biometricEnabled,
    val backupPin: String = "",
    val appIconVariant: String = defaultSettings.appIconVariant,
    val appIconStyle: String = defaultSettings.appIconStyle,
    val simpleFailoverAwgProfileId: String = defaultSettings.simpleFailoverAwgProfileId,
)

internal fun JsonObjectBuilder.writeRootUiRuntimeSnapshot(snapshot: AppSettingsRootUiRuntimeSnapshot) {
    put("appTheme", snapshot.appTheme)
    put("mode", snapshot.mode.preferenceValue)
    put("onboardingComplete", snapshot.onboardingComplete)
    put("webrtcProtectionEnabled", snapshot.webrtcProtectionEnabled)
    put("biometricEnabled", snapshot.biometricEnabled)
    put("appIconVariant", snapshot.appIconVariant)
    put("appIconStyle", snapshot.appIconStyle)
    put("simpleFailoverAwgProfileId", snapshot.simpleFailoverAwgProfileId)
}

internal fun JsonObject.readRootUiRuntimeSnapshot(
    defaults: AppSettingsRootUiRuntimeSnapshot,
): AppSettingsRootUiRuntimeSnapshot =
    AppSettingsRootUiRuntimeSnapshot(
        appTheme = stringValue("appTheme", defaults.appTheme),
        mode = Mode.fromString(stringValue("mode", defaults.mode.preferenceValue)),
        onboardingComplete = booleanValue("onboardingComplete", defaults.onboardingComplete),
        webrtcProtectionEnabled = booleanValue("webrtcProtectionEnabled", defaults.webrtcProtectionEnabled),
        biometricEnabled = booleanValue("biometricEnabled", defaults.biometricEnabled),
        backupPin = "",
        appIconVariant = stringValue("appIconVariant", defaults.appIconVariant),
        appIconStyle = stringValue("appIconStyle", defaults.appIconStyle),
        simpleFailoverAwgProfileId = stringValue("simpleFailoverAwgProfileId", defaults.simpleFailoverAwgProfileId),
    )
