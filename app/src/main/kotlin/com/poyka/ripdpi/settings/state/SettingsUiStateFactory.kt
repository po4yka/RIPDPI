package com.poyka.ripdpi.settings.state

import com.poyka.ripdpi.activities.LauncherIconManager
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultAppRoutingRussianPresetId
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.effectiveAppRoutingEnabledPresetIds
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot
import com.poyka.ripdpi.ui.state.SettingsUiState

internal fun AppSettings.toUiState(
    isHydrated: Boolean = true,
    serviceStatus: AppStatus = AppStatus.Halted,
    proxyTelemetry: NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "proxy"),
    serviceTelemetry: ServiceTelemetrySnapshot = ServiceTelemetrySnapshot(),
    hostAutolearnStorePresent: Boolean = false,
    rememberedNetworkCount: Int = 0,
    runtimeOverrideRememberedPolicy: Boolean = false,
    biometricAvailability: Int = androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS,
    routingProtectionSnapshot: RoutingProtectionCatalogSnapshot = RoutingProtectionCatalogSnapshot(),
    suggestedWarpAmneziaPresetId: String = "",
    suggestedWarpAmneziaPresetLabel: String = "",
    seqovlSupported: Boolean = false,
): SettingsUiState {
    val normalizedMode = ripdpiMode.ifEmpty { "vpn" }
    val activeDns = activeDnsSettings()
    val chain = analyzeChainFlags()
    val isVpn = normalizedMode == "vpn"

    return SettingsUiState(
        settings = this,
        appTheme = appTheme.ifEmpty { "system" },
        appIconVariant = LauncherIconManager.normalizeIconKey(appIconVariant),
        themedAppIconEnabled =
            LauncherIconManager.normalizeIconStyle(appIconStyle) == LauncherIconManager.ThemedIconStyle,
        ripdpiMode = normalizedMode,
        dns = buildDnsUiState(activeDns),
        ipv6Enable = ipv6Enable,
        enableCmdSettings = enableCmdSettings,
        cmdArgs = cmdArgs,
        proxy = buildProxyUiState(),
        desync = buildDesyncUiState(chain),
        fake = buildFakeUiState(),
        desyncHttp = desyncHttp,
        desyncHttps = desyncHttps,
        desyncUdp = desyncUdp,
        hostsMode = hostsMode.ifEmpty { "disable" },
        hostsBlacklist = hostsBlacklist,
        hostsWhitelist = hostsWhitelist,
        tlsPrelude = buildTlsPreludeUiState(chain),
        quic = buildQuicUiState(),
        detectionResistance = buildDetectionResistanceUiState(),
        warp = buildWarpUiState(suggestedWarpAmneziaPresetId, suggestedWarpAmneziaPresetLabel),
        routingProtection = buildRoutingProtectionUiState(routingProtectionSnapshot, serviceTelemetry),
        autolearn = buildAutolearnUiState(proxyTelemetry, hostAutolearnStorePresent, rememberedNetworkCount),
        adaptiveFallback = buildAdaptiveFallbackUiState(proxyTelemetry, runtimeOverrideRememberedPolicy),
        httpParser = buildHttpParserUiState(),
        onboardingComplete = onboardingComplete,
        webrtcProtectionEnabled = webrtcProtectionEnabled,
        excludeRussianAppsEnabled =
            effectiveAppRoutingEnabledPresetIds().contains(DefaultAppRoutingRussianPresetId),
        fullTunnelMode = fullTunnelMode,
        startOnBootEnabled = startOnBoot,
        biometricEnabled = biometricEnabled,
        biometricAvailability = biometricAvailability,
        backupPinHash = backupPin,
        diagnosticsMonitorEnabled = diagnosticsMonitorEnabled,
        diagnosticsSampleIntervalSeconds =
            diagnosticsSampleIntervalSeconds
                .takeIf { it > 0 }
                ?: AppSettingsSerializer.defaultValue.diagnosticsSampleIntervalSeconds,
        diagnosticsHistoryRetentionDays =
            diagnosticsHistoryRetentionDays
                .takeIf { it > 0 }
                ?: AppSettingsSerializer.defaultValue.diagnosticsHistoryRetentionDays,
        diagnosticsExportIncludeHistory = diagnosticsExportIncludeHistory,
        strategyPackAllowRollbackOverride = strategyPackAllowRollbackOverride,
        pcapCaptureEnabled = pcapCaptureEnabled,
        communityApiUrl = communityApiUrl,
        serviceStatus = serviceStatus,
        isVpn = isVpn,
        selectedMode = if (isVpn) Mode.VPN else Mode.Proxy,
        useCmdSettings = enableCmdSettings,
        desyncEnabled = chain.desyncEnabled,
        isFake = chain.isFake,
        usesFakeTransport = chain.usesFakeTransport,
        seqovlSupported = seqovlSupported,
        usesSeqOverlapFakeProfile = chain.usesSeqOverlapFakeProfile,
        hasHostFake = chain.hasHostFake,
        hasDisoob = chain.hasDisoob,
        isOob = chain.isOob,
        desyncHttpEnabled = chain.desyncHttpEnabled,
        desyncHttpsEnabled = chain.desyncHttpsEnabled,
        desyncUdpEnabled = chain.desyncUdpEnabled,
        tlsRecEnabled = chain.tlsRecEnabled,
        isHydrated = isHydrated,
    )
}
