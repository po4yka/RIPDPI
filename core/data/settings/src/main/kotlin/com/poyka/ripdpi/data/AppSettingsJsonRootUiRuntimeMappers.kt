package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

internal fun AppSettingsSnapshot.withUiRuntimeSnapshot(settings: AppSettings): AppSettingsSnapshot =
    copy(
        onboardingComplete = settings.onboardingComplete,
        webrtcProtectionEnabled = settings.webrtcProtectionEnabled,
        biometricEnabled = settings.biometricEnabled,
        appIconVariant = settings.appIconVariant,
        appIconStyle = settings.appIconStyle,
    )

internal fun AppSettings.Builder.applyRootUiRuntimeSnapshot(snapshot: AppSettingsSnapshot): AppSettings.Builder =
    setAppTheme(snapshot.appTheme)
        .setRipdpiMode(snapshot.mode.preferenceValue)
        .setIpv6Enable(snapshot.ipv6Enabled)
        .setEnableCmdSettings(snapshot.enableCommandLineSettings)
        .setCmdArgs(snapshot.commandLineArgs)
        .setOnboardingComplete(snapshot.onboardingComplete)
        .setWebrtcProtectionEnabled(snapshot.webrtcProtectionEnabled)
        .setBiometricEnabled(snapshot.biometricEnabled)
        .setAppIconVariant(snapshot.appIconVariant)
        .setAppIconStyle(snapshot.appIconStyle)
