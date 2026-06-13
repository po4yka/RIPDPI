package com.poyka.ripdpi.ui.state

import androidx.biometric.BiometricManager
import androidx.compose.runtime.Stable
import com.poyka.ripdpi.activities.AdaptiveFallbackUiState
import com.poyka.ripdpi.activities.DesyncCoreUiState
import com.poyka.ripdpi.activities.DetectionResistanceUiState
import com.poyka.ripdpi.activities.DnsUiState
import com.poyka.ripdpi.activities.FakeTransportUiState
import com.poyka.ripdpi.activities.HostAutolearnUiState
import com.poyka.ripdpi.activities.HttpParserUiState
import com.poyka.ripdpi.activities.LauncherIconManager
import com.poyka.ripdpi.activities.ProxyNetworkUiState
import com.poyka.ripdpi.activities.QuicUiState
import com.poyka.ripdpi.activities.RoutingProtectionUiState
import com.poyka.ripdpi.activities.TlsPreludeUiState
import com.poyka.ripdpi.activities.WarpUiState
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.proto.AppSettings

@Stable
data class SettingsUiState(
    val settings: AppSettings = AppSettingsSerializer.defaultValue,
    val appTheme: String = "system",
    val uiPersona: String = "simple",
    val appIconVariant: String = LauncherIconManager.DefaultIconKey,
    val themedAppIconEnabled: Boolean = true,
    val ripdpiMode: String = "vpn",
    val dns: DnsUiState = DnsUiState(),
    val ipv6Enable: Boolean = false,
    val enableCmdSettings: Boolean = false,
    val cmdArgs: String = "",
    val proxy: ProxyNetworkUiState = ProxyNetworkUiState(),
    val desync: DesyncCoreUiState = DesyncCoreUiState(),
    val fake: FakeTransportUiState = FakeTransportUiState(),
    val desyncHttp: Boolean = true,
    val desyncHttps: Boolean = true,
    val desyncUdp: Boolean = false,
    val hostsMode: String = "disable",
    val hostsBlacklist: String = "",
    val hostsWhitelist: String = "",
    val tlsPrelude: TlsPreludeUiState = TlsPreludeUiState(),
    val quic: QuicUiState = QuicUiState(),
    val detectionResistance: DetectionResistanceUiState = DetectionResistanceUiState(),
    val warp: WarpUiState = WarpUiState(),
    val routingProtection: RoutingProtectionUiState = RoutingProtectionUiState(),
    val autolearn: HostAutolearnUiState = HostAutolearnUiState(),
    val adaptiveFallback: AdaptiveFallbackUiState = AdaptiveFallbackUiState(),
    val httpParser: HttpParserUiState = HttpParserUiState(),
    val onboardingComplete: Boolean = false,
    val webrtcProtectionEnabled: Boolean = false,
    val excludeRussianAppsEnabled: Boolean = true,
    val fullTunnelMode: Boolean = false,
    val startOnBootEnabled: Boolean = false,
    val biometricEnabled: Boolean = false,
    val biometricAvailability: Int = BiometricManager.BIOMETRIC_SUCCESS,
    val backupPinHash: String = "",
    val diagnosticsMonitorEnabled: Boolean = true,
    val diagnosticsSampleIntervalSeconds: Int = 15,
    val diagnosticsHistoryRetentionDays: Int = 14,
    val diagnosticsExportIncludeHistory: Boolean = true,
    val strategyPackAllowRollbackOverride: Boolean = false,
    val pcapCaptureEnabled: Boolean = false,
    val rootModeEnabled: Boolean = false,
    val communityApiUrl: String = "",
    val serviceStatus: AppStatus = AppStatus.Halted,
    val isVpn: Boolean = true,
    val selectedMode: Mode = Mode.VPN,
    val useCmdSettings: Boolean = false,
    val desyncEnabled: Boolean = true,
    val isFake: Boolean = false,
    val usesFakeTransport: Boolean = false,
    val seqovlSupported: Boolean = false,
    val usesSeqOverlapFakeProfile: Boolean = false,
    val hasHostFake: Boolean = false,
    val hasDisoob: Boolean = false,
    val isOob: Boolean = false,
    val desyncHttpEnabled: Boolean = true,
    val desyncHttpsEnabled: Boolean = true,
    val desyncUdpEnabled: Boolean = false,
    val tlsRecEnabled: Boolean = false,
    val isHydrated: Boolean = true,
) {
    val hasBackupPin: Boolean
        get() = backupPinHash.isNotBlank()

    val isBiometricHardwareAvailable: Boolean
        get() = biometricAvailability == BiometricManager.BIOMETRIC_SUCCESS

    val isServiceRunning: Boolean
        get() = serviceStatus == AppStatus.Running

    val canForgetLearnedHosts: Boolean
        get() = !enableCmdSettings && autolearn.hostAutolearnStorePresent

    val canClearRememberedNetworks: Boolean
        get() = !enableCmdSettings && autolearn.rememberedNetworkCount > 0

    val canResetFakeTlsProfile: Boolean
        get() = !enableCmdSettings && fake.hasCustomFakeTlsProfile

    val canResetAdaptiveFakeTtlProfile: Boolean
        get() = !enableCmdSettings && fake.hasAdaptiveFakeTtl

    val canResetFakePayloadLibrary: Boolean
        get() = !enableCmdSettings && fake.hasCustomFakePayloadProfiles

    val canResetHttpParserEvasions: Boolean
        get() = !enableCmdSettings && httpParser.hasCustomHttpParserEvasions

    val canResetActivationWindow: Boolean
        get() = !enableCmdSettings && desync.hasCustomActivationWindow

    val warpUiAvailable: Boolean
        get() = !enableCmdSettings

    val httpFakeProfileActiveInStrategy: Boolean
        get() = desyncHttpEnabled && (isFake || usesSeqOverlapFakeProfile)

    val tlsFakeProfileActiveInStrategy: Boolean
        get() = desyncHttpsEnabled && (isFake || usesSeqOverlapFakeProfile)

    val udpFakeProfileActiveInStrategy: Boolean
        get() = desyncUdpEnabled && desync.hasUdpFakeBurst

    val fakePayloadLibraryControlsRelevant: Boolean
        get() = desyncHttpEnabled || desyncHttpsEnabled || desyncUdpEnabled

    val showFakePayloadLibrary: Boolean
        get() = enableCmdSettings || fakePayloadLibraryControlsRelevant || fake.hasCustomFakePayloadProfiles

    val fakeTlsControlsRelevant: Boolean
        get() = desyncHttpsEnabled && (isFake || usesSeqOverlapFakeProfile)

    val fakeTtlControlsRelevant: Boolean
        get() = usesFakeTransport || hasDisoob

    val showAdaptiveFakeTtlProfile: Boolean
        get() = enableCmdSettings || fakeTtlControlsRelevant || fake.hasAdaptiveFakeTtl

    val quicFakeControlsRelevant: Boolean
        get() = desyncUdpEnabled

    val showQuicFakeProfile: Boolean
        get() =
            enableCmdSettings || quicFakeControlsRelevant ||
                quic.quicFakeProfileActive || quic.quicFakeHost.isNotBlank()

    val hostFakeControlsRelevant: Boolean
        get() = desyncHttpEnabled || desyncHttpsEnabled

    val showHostFakeProfile: Boolean
        get() = enableCmdSettings || hasHostFake || hostFakeControlsRelevant

    val seqOverlapControlsRelevant: Boolean
        get() = desyncHttpEnabled || desyncHttpsEnabled

    val showSeqOverlapProfile: Boolean
        get() = enableCmdSettings || desync.hasSeqOverlap || seqOverlapControlsRelevant

    val seqOverlapUnavailableOnDevice: Boolean
        get() = !seqovlSupported && !desync.hasSeqOverlap

    val fakeApproximationControlsRelevant: Boolean
        get() = desyncHttpEnabled || desyncHttpsEnabled

    val showFakeApproximationProfile: Boolean
        get() = enableCmdSettings || desync.hasFakeApproximation || fakeApproximationControlsRelevant

    val fakeOrderingControlsRelevant: Boolean
        get() = desyncHttpEnabled || desyncHttpsEnabled

    val showFakeOrderingProfile: Boolean
        get() = enableCmdSettings || desync.hasFakeOrderingOverrides || fakeOrderingControlsRelevant

    val httpParserControlsRelevant: Boolean
        get() = desyncHttpEnabled

    val showHttpParserProfile: Boolean
        get() = enableCmdSettings || httpParserControlsRelevant || httpParser.hasCustomHttpParserEvasions

    val adaptiveFallbackControlsRelevant: Boolean
        get() = desyncEnabled

    val tlsPreludeControlsRelevant: Boolean
        get() = desyncHttpsEnabled

    val showTlsPreludeProfile: Boolean
        get() = enableCmdSettings || tlsPreludeControlsRelevant || tlsPrelude.tlsPreludeStepCount > 0

    val activationWindowControlsRelevant: Boolean
        get() = desyncEnabled

    val showActivationWindowProfile: Boolean
        get() =
            enableCmdSettings || activationWindowControlsRelevant ||
                desync.hasCustomActivationWindow || desync.hasStepActivationFilters

    val canResetAdaptiveSplitPreset: Boolean
        get() =
            !enableCmdSettings && desync.hasAdaptiveSplitPreset &&
                desync.adaptiveSplitVisualEditorSupported
}
