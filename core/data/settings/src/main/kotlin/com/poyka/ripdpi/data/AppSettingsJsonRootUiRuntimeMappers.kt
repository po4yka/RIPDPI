package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

internal fun AppSettingsSnapshot.withUiRuntimeSnapshot(settings: AppSettings): AppSettingsSnapshot =
    copy(
        rootUiRuntime =
            rootUiRuntime.copy(
                onboardingComplete = settings.onboardingComplete,
                webrtcProtectionEnabled = settings.webrtcProtectionEnabled,
                biometricEnabled = settings.biometricEnabled,
                appIconVariant = settings.appIconVariant,
                appIconStyle = settings.appIconStyle,
            ),
    )

internal fun AppSettings.Builder.applyRootUiRuntimeSnapshot(snapshot: AppSettingsSnapshot): AppSettings.Builder =
    setAppTheme(snapshot.rootUiRuntime.appTheme)
        .setRipdpiMode(snapshot.rootUiRuntime.mode.preferenceValue)
        .setIpv6Enable(snapshot.dns.ipv6Enabled)
        .setEnableCmdSettings(snapshot.proxyDesync.enableCommandLineSettings)
        .setCmdArgs(snapshot.proxyDesync.commandLineArgs)
        .setOnboardingComplete(snapshot.rootUiRuntime.onboardingComplete)
        .setWebrtcProtectionEnabled(snapshot.rootUiRuntime.webrtcProtectionEnabled)
        .setBiometricEnabled(snapshot.rootUiRuntime.biometricEnabled)
        .setAppIconVariant(snapshot.rootUiRuntime.appIconVariant)
        .setAppIconStyle(snapshot.rootUiRuntime.appIconStyle)
