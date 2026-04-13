package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.ActivationFilterModel
import com.poyka.ripdpi.data.AppRoutingPolicyModePrompt
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlDelta
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlMax
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlMin
import com.poyka.ripdpi.data.DefaultAdaptiveFallbackCachePrefixV4
import com.poyka.ripdpi.data.DefaultAdaptiveFallbackCacheTtlSeconds
import com.poyka.ripdpi.data.DefaultAppRoutingRussianPresetId
import com.poyka.ripdpi.data.DefaultEntropyPaddingMax
import com.poyka.ripdpi.data.DefaultEntropyPaddingTargetPermil
import com.poyka.ripdpi.data.DefaultEvolutionEpsilon
import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.DefaultHostAutolearnMaxHosts
import com.poyka.ripdpi.data.DefaultHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.DefaultRelayLocalSocksHost
import com.poyka.ripdpi.data.DefaultRelayLocalSocksPort
import com.poyka.ripdpi.data.DefaultShannonEntropyTargetPermil
import com.poyka.ripdpi.data.DefaultWarpLocalSocksPort
import com.poyka.ripdpi.data.DefaultWarpManualEndpointPort
import com.poyka.ripdpi.data.DefaultWarpScannerMaxRttMs
import com.poyka.ripdpi.data.DefaultWarpScannerParallelism
import com.poyka.ripdpi.data.DhtMitigationModeOff
import com.poyka.ripdpi.data.EntropyModeDisabled
import com.poyka.ripdpi.data.FakePayloadProfileCompatDefault
import com.poyka.ripdpi.data.FakeTlsSourceProfile
import com.poyka.ripdpi.data.QuicFakeProfileDisabled
import com.poyka.ripdpi.data.QuicInitialModeRouteAndCache
import com.poyka.ripdpi.data.RelayCongestionControlBbr
import com.poyka.ripdpi.data.RelayKindOff
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.WarpAmneziaPresetOff
import com.poyka.ripdpi.data.WarpEndpointSelectionAutomatic
import com.poyka.ripdpi.data.WarpRouteModeOff
import com.poyka.ripdpi.data.canonicalDefaultTcpChainSteps
import kotlinx.serialization.Serializable

data class RipDpiListenConfig(
    val ip: String = "127.0.0.1",
    val port: Int = 1080,
    val maxConnections: Int = 512,
    val bufferSize: Int = 16384,
    val tcpFastOpen: Boolean = false,
    val defaultTtl: Int = 0,
    val customTtl: Boolean = false,
    val freezeDetectionEnabled: Boolean = false,
)

data class RipDpiProtocolConfig(
    val resolveDomains: Boolean = true,
    val desyncHttp: Boolean = true,
    val desyncHttps: Boolean = true,
    val desyncUdp: Boolean = false,
)

data class RipDpiChainConfig(
    val groupActivationFilter: ActivationFilterModel = ActivationFilterModel(),
    val tcpSteps: List<TcpChainStepModel> = canonicalDefaultTcpChainSteps(),
    val tcpRotation: RipDpiTcpRotationConfig? = null,
    val udpSteps: List<UdpChainStepModel> = emptyList(),
    val anyProtocol: Boolean = false,
)

data class RipDpiTcpRotationCandidateConfig(
    val tcpSteps: List<TcpChainStepModel> = emptyList(),
)

data class RipDpiTcpRotationConfig(
    val fails: Int = 3,
    val retrans: Int = 3,
    val seq: Int = 65_536,
    val rst: Int = 1,
    val timeSecs: Long = 60,
    val candidates: List<RipDpiTcpRotationCandidateConfig> = emptyList(),
    val cancelOnFailure: Boolean = true,
)

data class RipDpiFakePacketConfig(
    val fakeTtl: Int = 8,
    val adaptiveFakeTtlEnabled: Boolean = false,
    val adaptiveFakeTtlDelta: Int = DefaultAdaptiveFakeTtlDelta,
    val adaptiveFakeTtlMin: Int = DefaultAdaptiveFakeTtlMin,
    val adaptiveFakeTtlMax: Int = DefaultAdaptiveFakeTtlMax,
    val adaptiveFakeTtlFallback: Int = DefaultAdaptiveFakeTtlFallback,
    val fakeSni: String = DefaultFakeSni,
    val httpFakeProfile: String = FakePayloadProfileCompatDefault,
    val fakeTlsSource: String = FakeTlsSourceProfile,
    val fakeTlsSecondaryProfile: String = "",
    val fakeTcpTimestampEnabled: Boolean = false,
    val fakeTcpTimestampDeltaTicks: Int = 0,
    val fakeTlsUseOriginal: Boolean = false,
    val fakeTlsRandomize: Boolean = false,
    val fakeTlsDupSessionId: Boolean = false,
    val fakeTlsPadEncap: Boolean = false,
    val fakeTlsSize: Int = 0,
    val fakeTlsSniMode: String = "fixed",
    val tlsFakeProfile: String = FakePayloadProfileCompatDefault,
    val udpFakeProfile: String = FakePayloadProfileCompatDefault,
    val fakeOffsetMarker: String = DefaultFakeOffsetMarker,
    val oobChar: Char = 'a',
    val dropSack: Boolean = false,
    val windowClamp: Int? = null,
    val wsizeWindow: Int? = null,
    val wsizeScale: Int? = null,
    val stripTimestamps: Boolean = false,
    val ipIdMode: String = "",
    val quicBindLowPort: Boolean = false,
    val quicMigrateAfterHandshake: Boolean = false,
    val entropyMode: String = EntropyModeDisabled,
    val entropyPaddingTargetPermil: Int = DefaultEntropyPaddingTargetPermil,
    val entropyPaddingMax: Int = DefaultEntropyPaddingMax,
    val shannonEntropyTargetPermil: Int = DefaultShannonEntropyTargetPermil,
    val tlsFingerprintProfile: String = TlsFingerprintProfileChromeStable,
)

data class RipDpiParserEvasionConfig(
    val hostMixedCase: Boolean = false,
    val domainMixedCase: Boolean = false,
    val hostRemoveSpaces: Boolean = false,
    val httpMethodEol: Boolean = false,
    val httpMethodSpace: Boolean = false,
    val httpUnixEol: Boolean = false,
    val httpHostPad: Boolean = false,
    val httpHostExtraSpace: Boolean = false,
    val httpHostTab: Boolean = false,
)

data class RipDpiAdaptiveFallbackConfig(
    val enabled: Boolean = true,
    val torst: Boolean = true,
    val tlsErr: Boolean = true,
    val httpRedirect: Boolean = true,
    val connectFailure: Boolean = true,
    val autoSort: Boolean = true,
    val cacheTtlSeconds: Int = DefaultAdaptiveFallbackCacheTtlSeconds,
    val cachePrefixV4: Int = DefaultAdaptiveFallbackCachePrefixV4,
    val strategyEvolution: Boolean = false,
    val evolutionEpsilon: Double = DefaultEvolutionEpsilon,
)

data class RipDpiQuicConfig(
    val initialMode: String = QuicInitialModeRouteAndCache,
    val supportV1: Boolean = true,
    val supportV2: Boolean = true,
    val fakeProfile: String = QuicFakeProfileDisabled,
    val fakeHost: String = "",
)

data class RipDpiHostsConfig(
    val mode: Mode = Mode.Disable,
    val entries: String? = null,
) {
    enum class Mode {
        Disable,
        Blacklist,
        Whitelist,
        ;

        companion object {
            fun fromWireName(name: String): Mode =
                when (name) {
                    "disable" -> Disable
                    "blacklist" -> Blacklist
                    "whitelist" -> Whitelist
                    else -> throw IllegalArgumentException("Unknown hosts mode: $name")
                }
        }

        val wireName: String
            get() =
                when (this) {
                    Disable -> "disable"
                    Blacklist -> "blacklist"
                    Whitelist -> "whitelist"
                }
    }
}

data class RipDpiHostAutolearnConfig(
    val enabled: Boolean = false,
    val penaltyTtlHours: Int = DefaultHostAutolearnPenaltyTtlHours,
    val maxHosts: Int = DefaultHostAutolearnMaxHosts,
    val storePath: String? = null,
    val networkScopeKey: String? = null,
)

data class RipDpiWsTunnelConfig(
    val enabled: Boolean = false,
    val mode: String? = null,
)

@Serializable
data class RipDpiRelayFinalmaskConfig(
    val type: String = com.poyka.ripdpi.data.RelayFinalmaskTypeOff,
    val headerHex: String = "",
    val trailerHex: String = "",
    val randRange: String = "",
    val sudokuSeed: String = "",
    val fragmentPackets: Int = 0,
    val fragmentMinBytes: Int = 0,
    val fragmentMaxBytes: Int = 0,
)

@Serializable
data class RipDpiRelayConfig(
    val enabled: Boolean = false,
    val kind: String = RelayKindOff,
    val profileId: String = "",
    val outboundBindIp: String = "",
    val server: String = "",
    val serverPort: Int = 443,
    val serverName: String = "",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val vlessTransport: String = RelayVlessTransportRealityTcp,
    val xhttpPath: String = "",
    val xhttpHost: String = "",
    val cloudflareTunnelMode: String = com.poyka.ripdpi.data.RelayCloudflareTunnelModeConsumeExisting,
    val cloudflarePublishLocalOriginUrl: String = "",
    val cloudflareCredentialsRef: String = "",
    val chainEntryServer: String = "",
    val chainEntryPort: Int = 443,
    val chainEntryServerName: String = "",
    val chainEntryPublicKey: String = "",
    val chainEntryShortId: String = "",
    val chainEntryProfileId: String = "",
    val chainExitServer: String = "",
    val chainExitPort: Int = 443,
    val chainExitServerName: String = "",
    val chainExitPublicKey: String = "",
    val chainExitShortId: String = "",
    val chainExitProfileId: String = "",
    val masqueUrl: String = "",
    val masqueUseHttp2Fallback: Boolean = true,
    val masqueCloudflareGeohashEnabled: Boolean = false,
    val tuicZeroRtt: Boolean = false,
    val tuicCongestionControl: String = RelayCongestionControlBbr,
    val shadowTlsInnerProfileId: String = "",
    val naivePath: String = "",
    val ptBridgeLine: String = "",
    val ptWebTunnelUrl: String = "",
    val ptSnowflakeBrokerUrl: String = "",
    val ptSnowflakeFrontDomain: String = "",
    val localSocksHost: String = DefaultRelayLocalSocksHost,
    val localSocksPort: Int = DefaultRelayLocalSocksPort,
    val udpEnabled: Boolean = false,
    val tcpFallbackEnabled: Boolean = true,
    val finalmask: RipDpiRelayFinalmaskConfig = RipDpiRelayFinalmaskConfig(),
)

@Serializable
data class RipDpiWarpManualEndpointConfig(
    val host: String = "",
    val ipv4: String = "",
    val ipv6: String = "",
    val port: Int = DefaultWarpManualEndpointPort,
)

@Serializable
data class RipDpiWarpAmneziaConfig(
    val enabled: Boolean = false,
    val jc: Int = 0,
    val jmin: Int = 0,
    val jmax: Int = 0,
    val h1: Long = 0L,
    val h2: Long = 0L,
    val h3: Long = 0L,
    val h4: Long = 0L,
    val s1: Int = 0,
    val s2: Int = 0,
    val s3: Int = 0,
    val s4: Int = 0,
)

@Serializable
data class RipDpiWarpConfig(
    val enabled: Boolean = false,
    val routeMode: String = WarpRouteModeOff,
    val routeHosts: String = "",
    val builtInRulesEnabled: Boolean = true,
    val endpointSelectionMode: String = WarpEndpointSelectionAutomatic,
    val manualEndpoint: RipDpiWarpManualEndpointConfig = RipDpiWarpManualEndpointConfig(),
    val scannerEnabled: Boolean = true,
    val scannerParallelism: Int = DefaultWarpScannerParallelism,
    val scannerMaxRttMs: Int = DefaultWarpScannerMaxRttMs,
    val amneziaPreset: String = WarpAmneziaPresetOff,
    val amnezia: RipDpiWarpAmneziaConfig = RipDpiWarpAmneziaConfig(),
    val localSocksHost: String = "127.0.0.1",
    val localSocksPort: Int = DefaultWarpLocalSocksPort,
)

data class RipDpiRoutingProtectionConfig(
    val appRoutingPolicyMode: String = AppRoutingPolicyModePrompt,
    val appRoutingEnabledPresetIds: List<String> = listOf(DefaultAppRoutingRussianPresetId),
    val antiCorrelationEnabled: Boolean = false,
    val dhtMitigationMode: String = DhtMitigationModeOff,
)
