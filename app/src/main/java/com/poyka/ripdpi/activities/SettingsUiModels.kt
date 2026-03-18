package com.poyka.ripdpi.activities

import androidx.compose.runtime.Stable
import com.poyka.ripdpi.data.ActivationFilterModel
import com.poyka.ripdpi.data.AdaptiveMarkerBalanced
import com.poyka.ripdpi.data.AdaptiveMarkerEndHost
import com.poyka.ripdpi.data.AdaptiveMarkerHost
import com.poyka.ripdpi.data.AdaptiveMarkerSniExt
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlDelta
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlMax
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlMin
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.DefaultHostAutolearnMaxHosts
import com.poyka.ripdpi.data.DefaultHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.DefaultQuicFakeHost
import com.poyka.ripdpi.data.DefaultSplitMarker
import com.poyka.ripdpi.data.DefaultTlsRandRecFragmentCount
import com.poyka.ripdpi.data.DefaultTlsRandRecMaxFragmentSize
import com.poyka.ripdpi.data.DefaultTlsRandRecMinFragmentSize
import com.poyka.ripdpi.data.DefaultTlsRecordMarker
import com.poyka.ripdpi.data.FakePayloadProfileCompatDefault
import com.poyka.ripdpi.data.FakeTlsSniModeFixed
import com.poyka.ripdpi.data.HostPackCatalogSnapshot
import com.poyka.ripdpi.data.HostPackPreset
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.QuicFakeProfileDisabled
import com.poyka.ripdpi.data.QuicFakeProfileRealisticInitial
import com.poyka.ripdpi.data.QuicInitialModeRouteAndCache
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.formatActivationFilterSummary
import com.poyka.ripdpi.data.isAdaptiveOffsetExpression
import com.poyka.ripdpi.data.primaryTcpChainStep
import com.poyka.ripdpi.data.supportsAdaptiveMarker
import com.poyka.ripdpi.proto.AppSettings
import java.security.MessageDigest

internal const val AdaptiveSplitPresetManual = "manual"
internal const val AdaptiveSplitPresetCustom = "custom"
internal const val AdaptiveFakeTtlModeFixed = "fixed"
internal const val AdaptiveFakeTtlModeAdaptive = "adaptive"
internal const val AdaptiveFakeTtlModeCustom = "custom"

internal typealias SettingsMutation = AppSettings.Builder.() -> Unit

internal fun hashPin(pin: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(pin.toByteArray()).joinToString("") { "%02x".format(it) }
}

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

@Stable
data class DnsUiState(
    val dnsIp: String = "1.1.1.1",
    val dnsMode: String = "encrypted",
    val dnsProviderId: String = "cloudflare",
    val dnsDohUrl: String = "https://cloudflare-dns.com/dns-query",
    val dnsDohBootstrapIps: List<String> = listOf("1.1.1.1", "1.0.0.1"),
    val encryptedDnsProtocol: String = com.poyka.ripdpi.data.EncryptedDnsProtocolDoh,
    val encryptedDnsHost: String = "cloudflare-dns.com",
    val encryptedDnsPort: Int = 443,
    val encryptedDnsTlsServerName: String = "cloudflare-dns.com",
    val encryptedDnsBootstrapIps: List<String> = listOf("1.1.1.1", "1.0.0.1"),
    val encryptedDnsDohUrl: String = "https://cloudflare-dns.com/dns-query",
    val encryptedDnsDnscryptProviderName: String = "",
    val encryptedDnsDnscryptPublicKey: String = "",
    val dnsSummary: String = "Encrypted DNS · Cloudflare (DoH)",
)

@Stable
data class QuicUiState(
    val quicInitialMode: String = QuicInitialModeRouteAndCache,
    val quicSupportV1: Boolean = true,
    val quicSupportV2: Boolean = true,
    val quicFakeProfile: String = QuicFakeProfileDisabled,
    val quicFakeHost: String = "",
) {
    val quicFakeProfileActive: Boolean
        get() = quicFakeProfile != QuicFakeProfileDisabled

    val showQuicFakeHostOverride: Boolean
        get() = quicFakeProfile == QuicFakeProfileRealisticInitial

    val quicFakeUsesCustomHost: Boolean
        get() = showQuicFakeHostOverride && quicFakeHost.isNotBlank()

    val quicFakeEffectiveHost: String
        get() =
            if (showQuicFakeHostOverride) {
                quicFakeHost.ifBlank { DefaultQuicFakeHost }
            } else {
                ""
            }
}

@Stable
data class HttpParserUiState(
    val hostMixedCase: Boolean = false,
    val domainMixedCase: Boolean = false,
    val hostRemoveSpaces: Boolean = false,
    val httpMethodEol: Boolean = false,
    val httpUnixEol: Boolean = false,
) {
    val httpParserSafeCount: Int
        get() = listOf(hostMixedCase, domainMixedCase, hostRemoveSpaces).count { it }

    val httpParserAggressiveCount: Int
        get() = listOf(httpMethodEol, httpUnixEol).count { it }

    val hasSafeHttpParserTweaks: Boolean
        get() = httpParserSafeCount > 0

    val hasAggressiveHttpParserEvasions: Boolean
        get() = httpParserAggressiveCount > 0

    val hasCustomHttpParserEvasions: Boolean
        get() = hasSafeHttpParserTweaks || hasAggressiveHttpParserEvasions
}

@Stable
data class HostAutolearnUiState(
    val hostAutolearnEnabled: Boolean = false,
    val hostAutolearnPenaltyTtlHours: Int = DefaultHostAutolearnPenaltyTtlHours,
    val hostAutolearnMaxHosts: Int = DefaultHostAutolearnMaxHosts,
    val networkStrategyMemoryEnabled: Boolean = false,
    val rememberedNetworkCount: Int = 0,
    val hostAutolearnRuntimeEnabled: Boolean = false,
    val hostAutolearnStorePresent: Boolean = false,
    val hostAutolearnLearnedHostCount: Int = 0,
    val hostAutolearnPenalizedHostCount: Int = 0,
    val hostAutolearnLastHost: String? = null,
    val hostAutolearnLastGroup: Int? = null,
    val hostAutolearnLastAction: String? = null,
)

@Stable
data class TlsPreludeUiState(
    val tlsrecEnabled: Boolean = false,
    val tlsrecMarker: String = DefaultTlsRecordMarker,
    val tlsPreludeMode: String = "disabled",
    val tlsPreludeStepCount: Int = 0,
    val tlsRandRecFragmentCount: Int = DefaultTlsRandRecFragmentCount,
    val tlsRandRecMinFragmentSize: Int = DefaultTlsRandRecMinFragmentSize,
    val tlsRandRecMaxFragmentSize: Int = DefaultTlsRandRecMaxFragmentSize,
) {
    val tlsPreludeUsesRandomRecords: Boolean
        get() = tlsPreludeMode == TcpChainStepKind.TlsRandRec.wireName

    val hasStackedTlsPreludeSteps: Boolean
        get() = tlsPreludeStepCount > 1
}

@Stable
data class ProxyNetworkUiState(
    val proxyIp: String = "127.0.0.1",
    val proxyPort: Int = 1080,
    val maxConnections: Int = 512,
    val bufferSize: Int = 16_384,
    val noDomain: Boolean = false,
    val tcpFastOpen: Boolean = false,
)

@Stable
data class FakeTransportUiState(
    val fakeTtl: Int = 8,
    val adaptiveFakeTtlEnabled: Boolean = false,
    val adaptiveFakeTtlDelta: Int = DefaultAdaptiveFakeTtlDelta,
    val adaptiveFakeTtlMin: Int = DefaultAdaptiveFakeTtlMin,
    val adaptiveFakeTtlMax: Int = DefaultAdaptiveFakeTtlMax,
    val adaptiveFakeTtlFallback: Int = DefaultAdaptiveFakeTtlFallback,
    val fakeSni: String = DefaultFakeSni,
    val fakeOffsetMarker: String = com.poyka.ripdpi.data.DefaultFakeOffsetMarker,
    val httpFakeProfile: String = FakePayloadProfileCompatDefault,
    val fakeTlsUseOriginal: Boolean = false,
    val fakeTlsRandomize: Boolean = false,
    val fakeTlsDupSessionId: Boolean = false,
    val fakeTlsPadEncap: Boolean = false,
    val fakeTlsSize: Int = 0,
    val fakeTlsSniMode: String = FakeTlsSniModeFixed,
    val tlsFakeProfile: String = FakePayloadProfileCompatDefault,
    val udpFakeProfile: String = FakePayloadProfileCompatDefault,
    val oobData: String = "a",
    val dropSack: Boolean = false,
) {
    val adaptiveFakeTtlMode: String
        get() =
            when {
                !adaptiveFakeTtlEnabled -> AdaptiveFakeTtlModeFixed
                adaptiveFakeTtlDelta == DefaultAdaptiveFakeTtlDelta -> AdaptiveFakeTtlModeAdaptive
                else -> AdaptiveFakeTtlModeCustom
            }

    val hasAdaptiveFakeTtl: Boolean
        get() = adaptiveFakeTtlEnabled

    val hasCustomAdaptiveFakeTtl: Boolean
        get() = adaptiveFakeTtlMode == AdaptiveFakeTtlModeCustom

    val hasCustomFakePayloadProfiles: Boolean
        get() =
            httpFakeProfile != FakePayloadProfileCompatDefault ||
                tlsFakeProfile != FakePayloadProfileCompatDefault ||
                udpFakeProfile != FakePayloadProfileCompatDefault

    val hasCustomFakeTlsProfile: Boolean
        get() =
            fakeTlsUseOriginal ||
                fakeTlsRandomize ||
                fakeTlsDupSessionId ||
                fakeTlsPadEncap ||
                fakeTlsSize != 0 ||
                fakeTlsSniMode != FakeTlsSniModeFixed ||
                (fakeTlsSniMode == FakeTlsSniModeFixed && fakeSni != DefaultFakeSni)
}

@Stable
data class DesyncCoreUiState(
    val desyncMethod: String = "disorder",
    val tcpChainSteps: List<TcpChainStepModel> = emptyList(),
    val udpChainSteps: List<UdpChainStepModel> = emptyList(),
    val groupActivationFilter: ActivationFilterModel = ActivationFilterModel(),
    val chainSummary: String = "tcp: none",
    val chainDsl: String = "",
    val splitMarker: String = DefaultSplitMarker,
    val udpFakeCount: Int = 0,
    val defaultTtl: Int = 0,
    val customTtl: Boolean = false,
    val hostFakeSteps: List<TcpChainStepModel> = tcpChainSteps.filter { it.kind == TcpChainStepKind.HostFake },
    val fakeApproximationSteps: List<TcpChainStepModel> =
        tcpChainSteps.filter {
            it.kind == TcpChainStepKind.FakeSplit || it.kind == TcpChainStepKind.FakeDisorder
        },
    val hasUdpFakeBurst: Boolean = udpChainSteps.any { it.count.coerceAtLeast(0) > 0 },
) {
    val hostFakeStepCount: Int
        get() = hostFakeSteps.size

    val primaryHostFakeStep: TcpChainStepModel?
        get() = hostFakeSteps.firstOrNull()

    val fakeApproximationStepCount: Int
        get() = fakeApproximationSteps.size

    val hasFakeApproximation: Boolean
        get() = fakeApproximationStepCount > 0

    val primaryFakeApproximationStep: TcpChainStepModel?
        get() = fakeApproximationSteps.firstOrNull()

    val hasFakeSplitApproximation: Boolean
        get() = fakeApproximationSteps.any { it.kind == TcpChainStepKind.FakeSplit }

    val hasFakeDisorderApproximation: Boolean
        get() = fakeApproximationSteps.any { it.kind == TcpChainStepKind.FakeDisorder }

    val hasCustomActivationWindow: Boolean
        get() = formatActivationFilterSummary(groupActivationFilter).isNotBlank()

    val stepActivationFilterCount: Int
        get() =
            tcpChainSteps.count { !it.activationFilter.isEmpty } +
                udpChainSteps.count { !it.activationFilter.isEmpty }

    val hasStepActivationFilters: Boolean
        get() = stepActivationFilterCount > 0

    val activationWindowSummary: String
        get() = formatActivationFilterSummary(groupActivationFilter).ifBlank { "Always active" }

    val adaptiveSplitPreset: String
        get() =
            when (splitMarker) {
                AdaptiveMarkerBalanced -> {
                    AdaptiveMarkerBalanced
                }

                AdaptiveMarkerHost -> {
                    AdaptiveMarkerHost
                }

                AdaptiveMarkerEndHost -> {
                    AdaptiveMarkerEndHost
                }

                AdaptiveMarkerSniExt -> {
                    AdaptiveMarkerSniExt
                }

                else -> {
                    if (isAdaptiveOffsetExpression(
                            splitMarker,
                        )
                    ) {
                        AdaptiveSplitPresetCustom
                    } else {
                        AdaptiveSplitPresetManual
                    }
                }
            }

    val hasAdaptiveSplitPreset: Boolean
        get() = adaptiveSplitPreset != AdaptiveSplitPresetManual

    val hasCustomAdaptiveSplitPreset: Boolean
        get() = adaptiveSplitPreset == AdaptiveSplitPresetCustom

    val adaptiveSplitVisualEditorSupported: Boolean
        get() = primaryTcpChainStep(tcpChainSteps)?.kind?.supportsAdaptiveMarker != false
}

@Stable
data class SettingsUiState(
    val settings: AppSettings = AppSettingsSerializer.defaultValue,
    val appTheme: String = "system",
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
    val autolearn: HostAutolearnUiState = HostAutolearnUiState(),
    val httpParser: HttpParserUiState = HttpParserUiState(),
    val onboardingComplete: Boolean = false,
    val webrtcProtectionEnabled: Boolean = false,
    val biometricEnabled: Boolean = false,
    val backupPinHash: String = "",
    val diagnosticsMonitorEnabled: Boolean = true,
    val diagnosticsSampleIntervalSeconds: Int = 15,
    val diagnosticsHistoryRetentionDays: Int = 14,
    val diagnosticsExportIncludeHistory: Boolean = true,
    val serviceStatus: AppStatus = AppStatus.Halted,
    val isVpn: Boolean = true,
    val selectedMode: Mode = Mode.VPN,
    val useCmdSettings: Boolean = false,
    val desyncEnabled: Boolean = true,
    val isFake: Boolean = false,
    val usesFakeTransport: Boolean = false,
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

    val httpFakeProfileActiveInStrategy: Boolean
        get() = desyncHttpEnabled && isFake

    val tlsFakeProfileActiveInStrategy: Boolean
        get() = desyncHttpsEnabled && isFake

    val udpFakeProfileActiveInStrategy: Boolean
        get() = desyncUdpEnabled && desync.hasUdpFakeBurst

    val fakePayloadLibraryControlsRelevant: Boolean
        get() = desyncHttpEnabled || desyncHttpsEnabled || desyncUdpEnabled

    val showFakePayloadLibrary: Boolean
        get() = enableCmdSettings || fakePayloadLibraryControlsRelevant || fake.hasCustomFakePayloadProfiles

    val fakeTlsControlsRelevant: Boolean
        get() = desyncHttpsEnabled && isFake

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

    val fakeApproximationControlsRelevant: Boolean
        get() = desyncHttpEnabled || desyncHttpsEnabled

    val showFakeApproximationProfile: Boolean
        get() = enableCmdSettings || desync.hasFakeApproximation || fakeApproximationControlsRelevant

    val httpParserControlsRelevant: Boolean
        get() = desyncHttpEnabled

    val showHttpParserProfile: Boolean
        get() = enableCmdSettings || httpParserControlsRelevant || httpParser.hasCustomHttpParserEvasions

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

@Stable
data class HostPackCatalogUiState(
    val snapshot: HostPackCatalogSnapshot = HostPackCatalogSnapshot(),
    val isRefreshing: Boolean = false,
) {
    val presets: List<HostPackPreset>
        get() = snapshot.packs
}
