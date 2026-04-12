package com.poyka.ripdpi.activities

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultRelayLocalSocksPort
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.DefaultSnowflakeBrokerUrl
import com.poyka.ripdpi.data.DefaultSnowflakeFrontDomain
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.RelayCloudflareTunnelModeConsumeExisting
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.data.RelayCongestionControlBbr
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayFinalmaskTypeFragment
import com.poyka.ripdpi.data.RelayFinalmaskTypeHeaderCustom
import com.poyka.ripdpi.data.RelayFinalmaskTypeNoise
import com.poyka.ripdpi.data.RelayFinalmaskTypeOff
import com.poyka.ripdpi.data.RelayFinalmaskTypeSudoku
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindOff
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayMasqueAuthModePreshared
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayPresetCatalog
import com.poyka.ripdpi.data.RelayPresetDefinition
import com.poyka.ripdpi.data.RelayPresetSuggestion
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.ServerCapabilityObservation
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.StrategyChainSet
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.formatChainSummary
import com.poyka.ripdpi.data.formatStrategyChainDsl
import com.poyka.ripdpi.data.normalizeRelayCloudflareTunnelMode
import com.poyka.ripdpi.data.normalizeRelayCongestionControl
import com.poyka.ripdpi.data.normalizeRelayFinalmaskType
import com.poyka.ripdpi.data.normalizeRelayMasqueAuthMode
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.primaryDesyncMethod
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.data.toRelaySettingsModel
import com.poyka.ripdpi.data.validateStrategyChainUsage
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.security.ImportedMasqueClientIdentity
import com.poyka.ripdpi.security.MasqueClientCredentialImporter
import com.poyka.ripdpi.services.MasquePrivacyPassAvailability
import com.poyka.ripdpi.services.MasquePrivacyPassBuildStatus
import com.poyka.ripdpi.utility.checkIp
import com.poyka.ripdpi.utility.validateIntRange
import com.poyka.ripdpi.utility.validatePort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import com.poyka.ripdpi.data.FailureClass as RuntimeFailureClass

internal const val defaultTtlMax = 255
internal const val defaultRelayPort = 443
internal const val bufferSizeDiv = 4
internal const val relayFinalmaskFragmentPacketsMin = 1
internal const val relayFinalmaskFragmentPacketsMax = 16
internal const val relayFinalmaskFragmentBytesMin = 1
internal const val relayFinalmaskFragmentBytesMax = 65535

private val DefaultConfigDnsSeed = canonicalDefaultEncryptedDnsSettings()

data class ConfigDraft(
    val mode: Mode = Mode.VPN,
    val dnsIp: String = DefaultConfigDnsSeed.dnsIp,
    val dnsSummary: String = DefaultConfigDnsSeed.summary(),
    val proxyIp: String = "127.0.0.1",
    val proxyPort: String = "1080",
    val maxConnections: String = "512",
    val bufferSize: String = "16384",
    val useCommandLineSettings: Boolean = false,
    val commandLineArgs: String = "",
    val tcpChainSteps: ImmutableList<TcpChainStepModel> = persistentListOf(),
    val udpChainSteps: ImmutableList<UdpChainStepModel> = persistentListOf(),
    val chainDsl: String = "",
    val desyncMethod: String = "split",
    val defaultTtl: String = "",
    val relayEnabled: Boolean = false,
    val relayKind: String = RelayKindOff,
    val relayProfileId: String = DefaultRelayProfileId,
    val relayPresetId: String = "",
    val relayServer: String = "",
    val relayServerPort: String = "443",
    val relayServerName: String = "",
    val relayRealityPublicKey: String = "",
    val relayRealityShortId: String = "",
    val relayVlessTransport: String = RelayVlessTransportRealityTcp,
    val relayXhttpPath: String = "",
    val relayXhttpHost: String = "",
    val relayCloudflareTunnelMode: String = RelayCloudflareTunnelModeConsumeExisting,
    val relayCloudflarePublishLocalOriginUrl: String = "",
    val relayCloudflareCredentialsRef: String = "",
    val relayCloudflareTunnelToken: String = "",
    val relayCloudflareTunnelCredentialsJson: String = "",
    val relayVlessUuid: String = "",
    val relayHysteriaPassword: String = "",
    val relayHysteriaSalamanderKey: String = "",
    val relayChainEntryServer: String = "",
    val relayChainEntryPort: String = "443",
    val relayChainEntryServerName: String = "",
    val relayChainEntryPublicKey: String = "",
    val relayChainEntryShortId: String = "",
    val relayChainEntryUuid: String = "",
    val relayChainEntryProfileId: String = "",
    val relayChainExitServer: String = "",
    val relayChainExitPort: String = "443",
    val relayChainExitServerName: String = "",
    val relayChainExitPublicKey: String = "",
    val relayChainExitShortId: String = "",
    val relayChainExitUuid: String = "",
    val relayChainExitProfileId: String = "",
    val relayMasqueUrl: String = "",
    val relayMasqueAuthMode: String = RelayMasqueAuthModeBearer,
    val relayMasqueAuthToken: String = "",
    val relayMasqueClientCertificateChainPem: String = "",
    val relayMasqueClientPrivateKeyPem: String = "",
    val relayMasqueUseHttp2Fallback: Boolean = true,
    val relayMasqueCloudflareGeohashEnabled: Boolean = false,
    val relayTuicUuid: String = "",
    val relayTuicPassword: String = "",
    val relayTuicZeroRtt: Boolean = false,
    val relayTuicCongestionControl: String = RelayCongestionControlBbr,
    val relayShadowTlsPassword: String = "",
    val relayShadowTlsInnerProfileId: String = "",
    val relayNaiveUsername: String = "",
    val relayNaivePassword: String = "",
    val relayNaivePath: String = "",
    val relayPtBridgeLine: String = "",
    val relayWebTunnelUrl: String = "",
    val relaySnowflakeBrokerUrl: String = DefaultSnowflakeBrokerUrl,
    val relaySnowflakeFrontDomain: String = DefaultSnowflakeFrontDomain,
    val relayUdpEnabled: Boolean = false,
    val relayLocalSocksPort: String = DefaultRelayLocalSocksPort.toString(),
    val relayFinalmaskType: String = RelayFinalmaskTypeOff,
    val relayFinalmaskHeaderHex: String = "",
    val relayFinalmaskTrailerHex: String = "",
    val relayFinalmaskRandRange: String = "",
    val relayFinalmaskSudokuSeed: String = "",
    val relayFinalmaskFragmentPackets: String = "",
    val relayFinalmaskFragmentMinBytes: String = "",
    val relayFinalmaskFragmentMaxBytes: String = "",
) {
    val chainSummary: String
        get() = resolvedChainSet().let { formatChainSummary(it.tcpSteps, it.udpSteps) }

    val relaySummary: String
        get() =
            when {
                !relayEnabled || relayKind == RelayKindOff -> "Disabled"
                relayKind == RelayKindChainRelay -> "Chain relay"
                relayKind == RelayKindMasque -> "MASQUE"
                relayKind == RelayKindHysteria2 -> "Hysteria2"
                relayKind == RelayKindCloudflareTunnel -> "Cloudflare Tunnel"
                relayKind == RelayKindTuicV5 -> "TUIC v5"
                relayKind == RelayKindShadowTlsV3 -> "ShadowTLS v3"
                relayKind == RelayKindNaiveProxy -> "NaiveProxy"
                relayKind == RelayKindSnowflake -> "Snowflake"
                relayKind == RelayKindWebTunnel -> "WebTunnel"
                relayKind == RelayKindObfs4 -> "obfs4"
                else -> "VLESS + Reality"
            }

    fun resolvedChainSet(): StrategyChainSet =
        parseStrategyChainDsl(chainDsl).getOrNull()
            ?: StrategyChainSet(tcpSteps = tcpChainSteps, udpSteps = udpChainSteps)

    fun withChainDsl(value: String): ConfigDraft {
        val parsed = parseStrategyChainDsl(value).getOrNull()
        return copy(
            chainDsl = value,
            tcpChainSteps = parsed?.tcpSteps?.toImmutableList() ?: tcpChainSteps,
            udpChainSteps = parsed?.udpSteps?.toImmutableList() ?: udpChainSteps,
            desyncMethod = parsed?.let { primaryDesyncMethod(it.tcpSteps) } ?: desyncMethod,
        )
    }
}

enum class ConfigPresetKind {
    Recommended,
    Proxy,
    Custom,
}

data class ConfigPreset(
    val id: String,
    val kind: ConfigPresetKind,
    val draft: ConfigDraft,
    val isSelected: Boolean = false,
)

data class ConfigUiState(
    val activeMode: Mode = Mode.VPN,
    val presets: ImmutableList<ConfigPreset> = buildConfigPresets(AppSettingsSerializer.defaultValue.toConfigDraft()),
    val editingPreset: ConfigPreset? = null,
    val draft: ConfigDraft = AppSettingsSerializer.defaultValue.toConfigDraft(),
    val validationErrors: ImmutableMap<String, String> = persistentMapOf(),
    val relayPresets: ImmutableList<RelayPresetUiState> = persistentListOf(),
    val relayPresetSuggestion: RelayPresetSuggestionUiState? = null,
    val supportsMasquePrivacyPass: Boolean = false,
    val masquePrivacyPassBuildStatus: MasquePrivacyPassBuildStatus = MasquePrivacyPassBuildStatus.MissingProviderUrl,
)

data class RelayPresetUiState(
    val id: String,
    val title: String,
    val selected: Boolean,
)

data class RelayPresetSuggestionUiState(
    val presetId: String,
    val title: String,
    val reason: String,
)

sealed interface ConfigEffect {
    data object SaveSuccess : ConfigEffect

    data object ValidationFailed : ConfigEffect

    data class Message(
        val text: String,
    ) : ConfigEffect
}

internal data class ConfigEditorSession(
    val presetId: String? = null,
    val draft: ConfigDraft? = null,
)

internal const val ConfigFieldDnsIp = "dnsIp"
internal const val ConfigFieldProxyIp = "proxyIp"
internal const val ConfigFieldProxyPort = "proxyPort"
internal const val ConfigFieldMaxConnections = "maxConnections"
internal const val ConfigFieldBufferSize = "bufferSize"
internal const val ConfigFieldDefaultTtl = "defaultTtl"
internal const val ConfigFieldStrategyChain = "strategyChain"
internal const val ConfigFieldRelayServerPort = "relayServerPort"
internal const val ConfigFieldRelayLocalSocksPort = "relayLocalSocksPort"
internal const val ConfigFieldRelayServer = "relayServer"
internal const val ConfigFieldRelayCredentials = "relayCredentials"
internal const val ConfigFieldRelayCloudflarePublishOrigin = "relayCloudflarePublishOrigin"
internal const val ConfigFieldRelayFinalmask = "relayFinalmask"

internal const val LegacyChainEntryProfileSuffix = "__ripdpi_chain_entry"
internal const val LegacyChainExitProfileSuffix = "__ripdpi_chain_exit"

internal fun AppSettings.toConfigDraft(): ConfigDraft =
    toRelaySettingsModel().let { relay ->
        ConfigDraft(
            mode = Mode.fromString(ripdpiMode.ifEmpty { "vpn" }),
            dnsIp = activeDnsSettings().dnsIp,
            dnsSummary = activeDnsSettings().summary(),
            proxyIp = proxyIp.ifEmpty { "127.0.0.1" },
            proxyPort = (proxyPort.takeIf { it > 0 } ?: 1080).toString(),
            maxConnections = (maxConnections.takeIf { it > 0 } ?: 512).toString(),
            bufferSize = (bufferSize.takeIf { it > 0 } ?: 16_384).toString(),
            useCommandLineSettings = enableCmdSettings,
            commandLineArgs = cmdArgs,
            tcpChainSteps = effectiveTcpChainSteps().toImmutableList(),
            udpChainSteps = effectiveUdpChainSteps().toImmutableList(),
            chainDsl = formatStrategyChainDsl(effectiveTcpChainSteps(), effectiveUdpChainSteps()),
            desyncMethod = primaryDesyncMethod(effectiveTcpChainSteps()).ifEmpty { "none" },
            defaultTtl = if (customTtl && defaultTtl > 0) defaultTtl.toString() else "",
            relayEnabled = relay.enabled,
            relayKind = relay.kind,
            relayProfileId = relay.profileId,
            relayPresetId = relay.profile.presetId,
            relayServer = relay.profile.server,
            relayServerPort = relay.profile.serverPort.toString(),
            relayServerName = relay.profile.serverName,
            relayRealityPublicKey = relay.profile.realityPublicKey,
            relayRealityShortId = relay.profile.realityShortId,
            relayVlessTransport = relay.profile.vlessTransport,
            relayXhttpPath = relay.profile.xhttpPath,
            relayXhttpHost = relay.profile.xhttpHost,
            relayCloudflareTunnelMode = relay.profile.cloudflareTunnelMode,
            relayCloudflarePublishLocalOriginUrl = relay.profile.cloudflarePublishLocalOriginUrl,
            relayCloudflareCredentialsRef = relay.profile.cloudflareCredentialsRef,
            relayChainEntryServer = relay.profile.chainEntryServer,
            relayChainEntryPort = relay.profile.chainEntryPort.toString(),
            relayChainEntryServerName = relay.profile.chainEntryServerName,
            relayChainEntryPublicKey = relay.profile.chainEntryPublicKey,
            relayChainEntryShortId = relay.profile.chainEntryShortId,
            relayChainEntryProfileId = relay.profile.chainEntryProfileId,
            relayChainExitServer = relay.profile.chainExitServer,
            relayChainExitPort = relay.profile.chainExitPort.toString(),
            relayChainExitServerName = relay.profile.chainExitServerName,
            relayChainExitPublicKey = relay.profile.chainExitPublicKey,
            relayChainExitShortId = relay.profile.chainExitShortId,
            relayChainExitProfileId = relay.profile.chainExitProfileId,
            relayMasqueUrl = relay.profile.masqueUrl,
            relayMasqueAuthMode = RelayMasqueAuthModeBearer,
            relayMasqueUseHttp2Fallback = relay.profile.masqueUseHttp2Fallback,
            relayMasqueCloudflareGeohashEnabled = relay.profile.masqueCloudflareGeohashEnabled,
            relayTuicZeroRtt = relay.profile.tuicZeroRtt,
            relayTuicCongestionControl = relay.profile.tuicCongestionControl,
            relayShadowTlsInnerProfileId = relay.profile.shadowTlsInnerProfileId,
            relayNaivePath = relay.profile.naivePath,
            relayPtBridgeLine = relay.profile.ptBridgeLine,
            relayWebTunnelUrl = relay.profile.ptWebTunnelUrl,
            relaySnowflakeBrokerUrl = relay.profile.ptSnowflakeBrokerUrl,
            relaySnowflakeFrontDomain = relay.profile.ptSnowflakeFrontDomain,
            relayUdpEnabled = relay.profile.udpEnabled,
            relayLocalSocksPort = relay.profile.localSocksPort.toString(),
            relayFinalmaskType = relay.profile.finalmask.type,
            relayFinalmaskHeaderHex = relay.profile.finalmask.headerHex,
            relayFinalmaskTrailerHex = relay.profile.finalmask.trailerHex,
            relayFinalmaskRandRange = relay.profile.finalmask.randRange,
            relayFinalmaskSudokuSeed = relay.profile.finalmask.sudokuSeed,
            relayFinalmaskFragmentPackets =
                relay.profile.finalmask.fragmentPackets
                    .takeIf { it > 0 }
                    ?.toString()
                    .orEmpty(),
            relayFinalmaskFragmentMinBytes =
                relay.profile.finalmask.fragmentMinBytes
                    .takeIf { it > 0 }
                    ?.toString()
                    .orEmpty(),
            relayFinalmaskFragmentMaxBytes =
                relay.profile.finalmask.fragmentMaxBytes
                    .takeIf { it > 0 }
                    ?.toString()
                    .orEmpty(),
        )
    }

internal fun buildConfigPresets(currentDraft: ConfigDraft): ImmutableList<ConfigPreset> {
    val recommendedDraft = AppSettingsSerializer.defaultValue.toConfigDraft()
    val proxyDraft = recommendedDraft.copy(mode = Mode.Proxy)
    val selectedId =
        when (currentDraft) {
            recommendedDraft -> "recommended"
            proxyDraft -> "proxy"
            else -> "custom"
        }

    return persistentListOf(
        ConfigPreset(
            id = "recommended",
            kind = ConfigPresetKind.Recommended,
            draft = recommendedDraft,
            isSelected = selectedId == "recommended",
        ),
        ConfigPreset(
            id = "proxy",
            kind = ConfigPresetKind.Proxy,
            draft = proxyDraft,
            isSelected = selectedId == "proxy",
        ),
        ConfigPreset(
            id = "custom",
            kind = ConfigPresetKind.Custom,
            draft = currentDraft,
            isSelected = selectedId == "custom",
        ),
    )
}

internal fun sanitizeMasqueAuthModeForCurrentBuild(
    draft: ConfigDraft,
    supportsMasquePrivacyPass: Boolean,
): ConfigDraft =
    if (!supportsMasquePrivacyPass && draft.relayMasqueAuthMode == RelayMasqueAuthModePrivacyPass) {
        draft.copy(
            relayMasqueAuthMode = RelayMasqueAuthModeBearer,
        )
    } else {
        draft
    }
