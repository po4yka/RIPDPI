package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.RelayCongestionControlBbr
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.effective
import com.poyka.ripdpi.data.nativeMieruHandshakeMode
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class RelaySocketProtection {
    @SerialName("inactive")
    Inactive,

    @SerialName("vpn_required")
    VpnRequired,
}

@Serializable
data class ResolvedRelayFinalmaskConfig(
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
data class ResolvedShadowTlsInnerRelayConfig(
    val kind: String,
    val profileId: String,
    val server: String,
    val serverPort: Int,
    val serverName: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val realityPublicKey: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val realityShortId: String = "",
    val vlessFlow: String = com.poyka.ripdpi.data.RelayVlessFlowVision,
    val vlessTransport: String = RelayVlessTransportRealityTcp,
    val xhttpMode: String = com.poyka.ripdpi.data.RelayXhttpModeAuto,
    val vlessUuid: String? = null,
    val tlsFingerprintProfile: String,
)

@Serializable
data class ResolvedChainRelayHopConfig(
    val kind: String = "",
    val profileId: String = "",
    val server: String = "",
    val serverPort: Int = 443,
    val serverName: String = "",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val vlessFlow: String = com.poyka.ripdpi.data.RelayVlessFlowVision,
    val vlessTransport: String = RelayVlessTransportRealityTcp,
    val xhttpPath: String = "",
    val xhttpHost: String = "",
    val xhttpMode: String = com.poyka.ripdpi.data.RelayXhttpModeAuto,
    val vlessMuxProtocol: String = "",
    val vlessMuxMaxConcurrentStreams: Int = 0,
    val vlessMuxPerConnectionKbps: Int = 0,
    val vlessMuxPaddingMax: Int = 0,
    val cloudflareTunnelMode: String = com.poyka.ripdpi.data.RelayCloudflareTunnelModeConsumeExisting,
    val cloudflarePublishLocalOriginUrl: String = "",
    val cloudflareCredentialsRef: String = "",
    val masqueUrl: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val masqueTcpProtocol: String = "http2",
    val masqueUseHttp2Fallback: Boolean = true,
    val masqueCloudflareGeohashEnabled: Boolean = false,
    val tuicZeroRtt: Boolean = false,
    val tuicCongestionControl: String = RelayCongestionControlBbr,
    val tuicRootCertificatePem: String? = null,
    val masqueRootCertificatePem: String? = null,
    val tuicAlpn: List<String>? = null,
    val shadowTlsInnerProfileId: String = "",
    val shadowTlsInner: ResolvedShadowTlsInnerRelayConfig? = null,
    val trojanRootCertificatePem: String? = null,
    val anytlsRootCertificatePem: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val anytlsCertificatePinSha256: String? = null,
    val naivePath: String = "",
    val vlessUuid: String? = null,
    val hysteriaPassword: String? = null,
    val hysteriaSalamanderKey: String? = null,
    val hysteriaInsecure: Boolean = false,
    val hysteriaPinSha256: String? = null,
    val hysteriaPortHopPorts: String? = null,
    val hysteriaPortHopIntervalMillis: Long? = null,
    val hysteriaPortHopIntervalMaxMillis: Long? = null,
    val hysteriaRealmId: String? = null,
    val hysteriaRealmServerUrl: String? = null,
    val hysteriaRealmToken: String? = null,
    val hysteriaRealmStunServers: List<String> = emptyList(),
    @SerialName("anytlsPassword")
    val anyTlsPassword: String? = null,
    val tuicUuid: String? = null,
    val tuicPassword: String? = null,
    val shadowTlsPassword: String? = null,
    val trojanPassword: String? = null,
    val shadowsocksMethod: String? = null,
    val shadowsocksPassword: String? = null,
    val naiveUsername: String? = null,
    val naivePassword: String? = null,
    val tlsFingerprintProfile: String,
    val masqueAuthMode: String? = null,
    val masqueAuthToken: String? = null,
    val masqueClientCertificateChainPem: String? = null,
    val masqueClientPrivateKeyPem: String? = null,
    val masqueCloudflareGeohashHeader: String? = null,
    val masquePrivacyPassProviderUrl: String? = null,
    val masquePrivacyPassProviderAuthToken: String? = null,
    val cloudflareTunnelToken: String? = null,
    val cloudflareTunnelCredentialsJson: String? = null,
    val finalmask: ResolvedRelayFinalmaskConfig = ResolvedRelayFinalmaskConfig(),
)

@Serializable
data class ResolvedTorPluggableTransportConfig(
    val protocols: List<String> = emptyList(),
    val binaryPath: String = "",
    val arguments: List<String> = emptyList(),
    val runOnStartup: Boolean = false,
)

@Serializable
data class ResolvedRipDpiRelayConfig(
    val enabled: Boolean,
    val kind: String,
    val profileId: String,
    val outboundBindIp: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val socketProtection: RelaySocketProtection = RelaySocketProtection.Inactive,
    val server: String,
    val serverPort: Int,
    val serverName: String,
    val realityPublicKey: String,
    val realityShortId: String,
    val vlessFlow: String = com.poyka.ripdpi.data.RelayVlessFlowVision,
    val vlessTransport: String = RelayVlessTransportRealityTcp,
    val xhttpPath: String = "",
    val xhttpHost: String = "",
    val xhttpMode: String = com.poyka.ripdpi.data.RelayXhttpModeAuto,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val xhttpMaxConnections: Int? = null,
    val vlessMuxProtocol: String = "",
    val vlessMuxMaxConcurrentStreams: Int = 0,
    val vlessMuxPerConnectionKbps: Int = 0,
    val vlessMuxPaddingMax: Int = 0,
    val cloudflareTunnelMode: String = com.poyka.ripdpi.data.RelayCloudflareTunnelModeConsumeExisting,
    val cloudflarePublishLocalOriginUrl: String = "",
    val cloudflareCredentialsRef: String = "",
    val chainEntry: ResolvedChainRelayHopConfig? = null,
    val chainEntryServer: String,
    val chainEntryPort: Int,
    val chainEntryServerName: String,
    val chainEntryPublicKey: String,
    val chainEntryShortId: String,
    val chainEntryProfileId: String = "",
    val chainExit: ResolvedChainRelayHopConfig? = null,
    val chainExitServer: String,
    val chainExitPort: Int,
    val chainExitServerName: String,
    val chainExitPublicKey: String,
    val chainExitShortId: String,
    val chainExitProfileId: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val chainHops: List<ResolvedChainRelayHopConfig> = emptyList(),
    val masqueUrl: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val masqueTcpProtocol: String = "http2",
    val masqueUseHttp2Fallback: Boolean,
    val masqueCloudflareGeohashEnabled: Boolean = false,
    val tuicZeroRtt: Boolean = false,
    val tuicCongestionControl: String = RelayCongestionControlBbr,
    val tuicRootCertificatePem: String? = null,
    val masqueRootCertificatePem: String? = null,
    val tuicAlpn: List<String>? = null,
    val shadowTlsInnerProfileId: String = "",
    val shadowTlsInner: ResolvedShadowTlsInnerRelayConfig? = null,
    val trojanRootCertificatePem: String? = null,
    val naivePath: String = "",
    val mieruUsername: String? = null,
    val mieruPassword: String? = null,
    val mieruProtocol: String = com.poyka.ripdpi.data.RelayMieruProtocolTcp,
    val mieruMultiplexing: String = com.poyka.ripdpi.data.RelayMieruMultiplexingMiddle,
    val mieruMtu: Int = com.poyka.ripdpi.data.RelayMieruMtuDefault,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val mieruHandshakeMode: String = com.poyka.ripdpi.data.MieruHandshakeStandard,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val mieruTrafficPattern: com.poyka.ripdpi.data.MieruTrafficPattern? = null,
    val sshHost: String = "",
    val sshPort: Int = 0,
    val sshUsername: String? = null,
    val sshAuthType: String = com.poyka.ripdpi.data.RelaySshAuthTypePassword,
    val sshPassword: String? = null,
    val sshPrivateKey: String? = null,
    val sshPrivateKeyPassphrase: String? = null,
    val sshHostKeyFingerprint: String? = null,
    val sshStrictHostKey: Boolean = false,
    @Transient val naiveExtraHeaders: String = "",
    @Transient val naiveServerPinSha256: String = "",
    @Transient val naiveQuic: Boolean = false,
    @Transient val naiveConcurrency: Int = 1,
    @Transient val ptBridgeLine: String = "",
    @Transient val ptWebTunnelUrl: String = "",
    @Transient val ptSnowflakeBrokerUrl: String = "",
    @Transient val ptSnowflakeFrontDomain: String = "",
    val torStateDir: String = "",
    val torCacheDir: String = "",
    val torBridgeLines: List<String> = emptyList(),
    val torTransports: List<ResolvedTorPluggableTransportConfig> = emptyList(),
    val localSocksHost: String,
    val localSocksPort: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val localSocksUsername: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val localSocksPassword: String? = null,
    val udpEnabled: Boolean,
    val tcpFallbackEnabled: Boolean,
    val quicBindLowPort: Boolean = false,
    val quicMigrateAfterHandshake: Boolean = false,
    val vlessUuid: String? = null,
    val chainEntryUuid: String? = null,
    val chainExitUuid: String? = null,
    val hysteriaPassword: String? = null,
    val hysteriaSalamanderKey: String? = null,
    val hysteriaPortHopPorts: String? = null,
    val hysteriaPortHopIntervalMillis: Long? = null,
    val hysteriaPortHopIntervalMaxMillis: Long? = null,
    val hysteriaRealmId: String? = null,
    val hysteriaRealmServerUrl: String? = null,
    val hysteriaRealmToken: String? = null,
    val hysteriaRealmStunServers: List<String> = emptyList(),
    val hysteriaInsecure: Boolean = false,
    val hysteriaPinSha256: String? = null,
    @SerialName("anytlsPassword")
    val anyTlsPassword: String? = null,
    @SerialName("anytlsCertificatePinSha256")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val anyTlsPinSha256: String? = null,
    val tuicUuid: String? = null,
    val tuicPassword: String? = null,
    val shadowTlsPassword: String? = null,
    val trojanPassword: String? = null,
    val snellPsk: String? = null,
    val snellUserKey: String? = null,
    val shadowsocksMethod: String? = null,
    val shadowsocksPassword: String? = null,
    val naiveUsername: String? = null,
    val naivePassword: String? = null,
    val tlsFingerprintProfile: String,
    val masqueAuthMode: String? = null,
    val masqueAuthToken: String? = null,
    val masqueClientCertificateChainPem: String? = null,
    val masqueClientPrivateKeyPem: String? = null,
    val masqueCloudflareGeohashHeader: String? = null,
    val masquePrivacyPassProviderUrl: String? = null,
    val masquePrivacyPassProviderAuthToken: String? = null,
    val cloudflareTunnelToken: String? = null,
    val cloudflareTunnelCredentialsJson: String? = null,
    val appsScriptScriptIds: List<String> = emptyList(),
    val appsScriptGoogleIp: String = "",
    val appsScriptFrontDomain: String = "",
    val appsScriptSniHosts: List<String> = emptyList(),
    val appsScriptVerifySsl: Boolean = com.poyka.ripdpi.data.DefaultRelayAppsScriptVerifySsl,
    val appsScriptParallelRelay: Boolean = false,
    val appsScriptDirectHosts: List<String> = emptyList(),
    val appsScriptAuthKey: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("dataDir")
    val appsScriptDataDir: String? = null,
    val finalmask: ResolvedRelayFinalmaskConfig = ResolvedRelayFinalmaskConfig(),
    @Required
    val schemaVersion: Int = RelayNativeConfigSchemaVersion,
) {
    override fun toString(): String = "ResolvedRipDpiRelayConfig(kind=$kind, profileId=$profileId, [REDACTED])"
}

const val RelayNativeConfigSchemaVersion: Int = 10

const val RelayChainMinHops: Int = 2

const val RelayChainMaxHops: Int = 4

class RelayChainHopCountException(
    val hopCount: Int,
    val minHops: Int = RelayChainMinHops,
    val maxHops: Int = RelayChainMaxHops,
) : IllegalArgumentException(
        "chain relay hop count $hopCount is out of range [$minHops, $maxHops]",
    )

data class ResolvedChainRelayHopRef(
    val config: ResolvedChainRelayHopConfig?,
    val server: String,
    val serverPort: Int,
    val serverName: String,
    val publicKey: String,
    val shortId: String,
    val flow: String,
    val xhttpMode: String,
    val profileId: String,
    val uuid: String?,
)

data class RelayConfigSections(
    val common: RelayCommonSection,
    val vless: RelayVlessSection,
    val chain: RelayChainSection,
    val masque: RelayMasqueSection,
    val tuic: RelayTuicSection,
    val shadowTls: RelayShadowTlsSection,
    val trojan: RelayTrojanSection,
    val snell: RelaySnellSection,
    val shadowsocks: RelayShadowsocksSection,
    val hysteria2: RelayHysteria2Section,
    val mieru: RelayMieruSection,
    val ssh: RelaySshSection,
    val anyTls: RelayAnyTlsSection,
    val pluggableTransport: RelayPluggableTransportSection,
    val tor: RelayTorSection,
    val cloudflare: RelayCloudflareSection,
    val appsScript: RelayAppsScriptSection,
    val finalmask: ResolvedRelayFinalmaskConfig,
)

private fun ResolvedRipDpiRelayConfig.commonSection(): RelayCommonSection =
    RelayCommonSection(
        enabled = enabled,
        kind = kind,
        profileId = profileId,
        outboundBindIp = outboundBindIp,
        server = server,
        serverPort = serverPort,
        serverName = serverName,
        localSocksHost = localSocksHost,
        localSocksPort = localSocksPort,
        localSocksCredentials = localSocksCredentialsOrNull(localSocksUsername, localSocksPassword),
        udpEnabled = udpEnabled,
        tcpFallbackEnabled = tcpFallbackEnabled,
        quicBindLowPort = quicBindLowPort,
        quicMigrateAfterHandshake = quicMigrateAfterHandshake,
        tlsFingerprintProfile = tlsFingerprintProfile,
        socketProtection = socketProtection,
    )

private fun ResolvedRipDpiRelayConfig.vlessSection(): RelayVlessSection =
    RelayVlessSection(
        realityPublicKey = realityPublicKey,
        realityShortId = realityShortId,
        vlessFlow = vlessFlow,
        vlessTransport = vlessTransport,
        xhttpPath = xhttpPath,
        xhttpHost = xhttpHost,
        xhttpMode = xhttpMode,
        xhttpMaxConnections = xhttpMaxConnections,
        vlessMuxProtocol = vlessMuxProtocol,
        vlessMuxMaxConcurrentStreams = vlessMuxMaxConcurrentStreams,
        vlessMuxPerConnectionKbps = vlessMuxPerConnectionKbps,
        vlessMuxPaddingMax = vlessMuxPaddingMax,
        vlessUuid = vlessUuid,
    )

internal fun ResolvedChainRelayHopConfig.toHopRef(): ResolvedChainRelayHopRef =
    ResolvedChainRelayHopRef(
        config = this,
        server = server,
        serverPort = serverPort,
        serverName = serverName,
        publicKey = realityPublicKey,
        shortId = realityShortId,
        flow = vlessFlow,
        xhttpMode = xhttpMode,
        profileId = profileId,
        uuid = vlessUuid,
    )

internal fun ResolvedChainRelayHopRef.toHopConfig(): ResolvedChainRelayHopConfig =
    requireNotNull(config) {
        "Resolved chain hop config with explicit TLS fingerprint is required"
    }

private fun ResolvedRipDpiRelayConfig.masqueSection(): RelayMasqueSection =
    RelayMasqueSection(
        masqueUrl = masqueUrl,
        masqueTcpProtocol = masqueTcpProtocol,
        masqueUseHttp2Fallback = masqueUseHttp2Fallback,
        masqueCloudflareGeohashEnabled = masqueCloudflareGeohashEnabled,
        masqueAuthMode = masqueAuthMode,
        masqueAuthToken = masqueAuthToken,
        masqueClientCertificateChainPem = masqueClientCertificateChainPem,
        masqueClientPrivateKeyPem = masqueClientPrivateKeyPem,
        masqueCloudflareGeohashHeader = masqueCloudflareGeohashHeader,
        masquePrivacyPassProviderUrl = masquePrivacyPassProviderUrl,
        masquePrivacyPassProviderAuthToken = masquePrivacyPassProviderAuthToken,
        masqueRootCertificatePem = masqueRootCertificatePem,
    )

private fun ResolvedRipDpiRelayConfig.tuicSection(): RelayTuicSection =
    RelayTuicSection(
        tuicZeroRtt = tuicZeroRtt,
        tuicCongestionControl = tuicCongestionControl,
        tuicRootCertificatePem = tuicRootCertificatePem,
        tuicAlpn = tuicAlpn,
        tuicUuid = tuicUuid,
        tuicPassword = tuicPassword,
    )

private fun ResolvedRipDpiRelayConfig.shadowTlsSection(): RelayShadowTlsSection =
    RelayShadowTlsSection(
        shadowTlsInnerProfileId = shadowTlsInnerProfileId,
        shadowTlsInner = shadowTlsInner,
        shadowTlsPassword = shadowTlsPassword,
    )

private fun ResolvedRipDpiRelayConfig.trojanSection(): RelayTrojanSection =
    RelayTrojanSection(
        trojanPassword = trojanPassword,
        trojanRootCertificatePem = trojanRootCertificatePem,
    )

private fun ResolvedRipDpiRelayConfig.snellSection(): RelaySnellSection =
    RelaySnellSection(snellPsk = snellPsk, snellUserKey = snellUserKey)

private fun ResolvedRipDpiRelayConfig.shadowsocksSection(): RelayShadowsocksSection =
    RelayShadowsocksSection(
        shadowsocksMethod = shadowsocksMethod,
        shadowsocksPassword = shadowsocksPassword,
    )

private fun ResolvedRipDpiRelayConfig.hysteria2Section(): RelayHysteria2Section =
    RelayHysteria2Section(
        hysteriaPassword = hysteriaPassword,
        hysteriaSalamanderKey = hysteriaSalamanderKey,
        hysteriaInsecure = hysteriaInsecure,
        hysteriaPinSha256 = hysteriaPinSha256,
        hysteriaPortHopPorts = hysteriaPortHopPorts,
        hysteriaPortHopIntervalMillis = hysteriaPortHopIntervalMillis,
        hysteriaPortHopIntervalMaxMillis = hysteriaPortHopIntervalMaxMillis,
        hysteriaRealmId = hysteriaRealmId,
        hysteriaRealmServerUrl = hysteriaRealmServerUrl,
        hysteriaRealmToken = hysteriaRealmToken,
        hysteriaRealmStunServers = hysteriaRealmStunServers,
    )

private fun ResolvedRipDpiRelayConfig.mieruSection(): RelayMieruSection =
    RelayMieruSection(
        mieruUsername = mieruUsername,
        mieruPassword = mieruPassword,
        mieruProtocol = mieruProtocol,
        mieruMultiplexing = mieruMultiplexing,
        mieruMtu = mieruMtu,
        mieruHandshakeMode = mieruHandshakeMode,
        mieruTrafficPattern = mieruTrafficPattern,
    )

private fun ResolvedRipDpiRelayConfig.sshSection(): RelaySshSection =
    RelaySshSection(
        sshHost = sshHost,
        sshPort = sshPort,
        sshUsername = sshUsername,
        sshAuthType = sshAuthType,
        sshPassword = sshPassword,
        sshPrivateKey = sshPrivateKey,
        sshPrivateKeyPassphrase = sshPrivateKeyPassphrase,
        sshHostKeyFingerprint = sshHostKeyFingerprint,
        sshStrictHostKey = sshStrictHostKey,
    )

private fun ResolvedRipDpiRelayConfig.anyTlsSection(): RelayAnyTlsSection =
    RelayAnyTlsSection(
        anyTlsPassword = anyTlsPassword,
        anyTlsPinSha256 = anyTlsPinSha256,
    )

private fun ResolvedRipDpiRelayConfig.pluggableTransportSection(): RelayPluggableTransportSection =
    RelayPluggableTransportSection(
        naivePath = naivePath,
        naiveUsername = naiveUsername,
        naivePassword = naivePassword,
        naiveExtraHeaders = naiveExtraHeaders,
        naiveServerPinSha256 = naiveServerPinSha256,
        naiveQuic = naiveQuic,
        naiveConcurrency = naiveConcurrency,
        ptBridgeLine = ptBridgeLine,
        ptWebTunnelUrl = ptWebTunnelUrl,
        ptSnowflakeBrokerUrl = ptSnowflakeBrokerUrl,
        ptSnowflakeFrontDomain = ptSnowflakeFrontDomain,
    )

private fun ResolvedRipDpiRelayConfig.torSection(): RelayTorSection =
    RelayTorSection(
        torStateDir = torStateDir,
        torCacheDir = torCacheDir,
        torBridgeLines = torBridgeLines,
        torTransports = torTransports,
    )

private fun ResolvedRipDpiRelayConfig.cloudflareSection(): RelayCloudflareSection =
    RelayCloudflareSection(
        cloudflareTunnelMode = cloudflareTunnelMode,
        cloudflarePublishLocalOriginUrl = cloudflarePublishLocalOriginUrl,
        cloudflareCredentialsRef = cloudflareCredentialsRef,
        cloudflareTunnelToken = cloudflareTunnelToken,
        cloudflareTunnelCredentialsJson = cloudflareTunnelCredentialsJson,
    )

private fun ResolvedRipDpiRelayConfig.appsScriptSection(): RelayAppsScriptSection =
    RelayAppsScriptSection(
        appsScriptScriptIds = appsScriptScriptIds,
        appsScriptGoogleIp = appsScriptGoogleIp,
        appsScriptFrontDomain = appsScriptFrontDomain,
        appsScriptSniHosts = appsScriptSniHosts,
        appsScriptVerifySsl = appsScriptVerifySsl,
        appsScriptParallelRelay = appsScriptParallelRelay,
        appsScriptDirectHosts = appsScriptDirectHosts,
        appsScriptAuthKey = appsScriptAuthKey,
        appsScriptDataDir = appsScriptDataDir,
    )

private fun ResolvedRipDpiRelayConfig.legacyTwoHopChainRefs(): List<ResolvedChainRelayHopRef> =
    listOf(
        ResolvedChainRelayHopRef(
            config = chainEntry,
            server = chainEntryServer,
            serverPort = chainEntryPort,
            serverName = chainEntryServerName,
            publicKey = chainEntryPublicKey,
            shortId = chainEntryShortId,
            flow = com.poyka.ripdpi.data.RelayVlessFlowVision,
            xhttpMode = com.poyka.ripdpi.data.RelayXhttpModeAuto,
            profileId = chainEntryProfileId,
            uuid = chainEntryUuid,
        ),
        ResolvedChainRelayHopRef(
            config = chainExit,
            server = chainExitServer,
            serverPort = chainExitPort,
            serverName = chainExitServerName,
            publicKey = chainExitPublicKey,
            shortId = chainExitShortId,
            flow = com.poyka.ripdpi.data.RelayVlessFlowVision,
            xhttpMode = com.poyka.ripdpi.data.RelayXhttpModeAuto,
            profileId = chainExitProfileId,
            uuid = chainExitUuid,
        ),
    )

private fun ResolvedRipDpiRelayConfig.orderedChainHopRefs(): List<ResolvedChainRelayHopRef> =
    chainHops.map(ResolvedChainRelayHopConfig::toHopRef).ifEmpty(this::legacyTwoHopChainRefs)

private fun ResolvedRipDpiRelayConfig.chainSection(): RelayChainSection =
    RelayChainSection(hops = orderedChainHopRefs())

internal fun RelayChainSection.wireChainHops(): List<ResolvedChainRelayHopConfig> =
    if (hops.size > RelayChainMinHops) hops.map(ResolvedChainRelayHopRef::toHopConfig) else emptyList()

fun ResolvedRipDpiRelayConfig.toSections(): RelayConfigSections =
    RelayConfigSections(
        common = commonSection(),
        vless = vlessSection(),
        chain = chainSection(),
        masque = masqueSection(),
        tuic = tuicSection(),
        shadowTls = shadowTlsSection(),
        trojan = trojanSection(),
        snell = snellSection(),
        shadowsocks = shadowsocksSection(),
        hysteria2 = hysteria2Section(),
        mieru = mieruSection(),
        ssh = sshSection(),
        anyTls = anyTlsSection(),
        pluggableTransport = pluggableTransportSection(),
        tor = torSection(),
        cloudflare = cloudflareSection(),
        appsScript = appsScriptSection(),
        finalmask = finalmask,
    )

@Suppress("LongMethod")
fun RelayConfigSections.toResolvedConfig(): ResolvedRipDpiRelayConfig =
    ResolvedRipDpiRelayConfig(
        enabled = common.enabled,
        kind = common.kind,
        profileId = common.profileId,
        outboundBindIp = common.outboundBindIp,
        socketProtection = common.socketProtection,
        server = common.server,
        serverPort = common.serverPort,
        serverName = common.serverName,
        realityPublicKey = vless.realityPublicKey,
        realityShortId = vless.realityShortId,
        vlessFlow = vless.vlessFlow,
        vlessTransport = vless.vlessTransport,
        vlessMuxProtocol = vless.vlessMuxProtocol,
        vlessMuxMaxConcurrentStreams = vless.vlessMuxMaxConcurrentStreams,
        vlessMuxPerConnectionKbps = vless.vlessMuxPerConnectionKbps,
        vlessMuxPaddingMax = vless.vlessMuxPaddingMax,
        cloudflareTunnelMode = cloudflare.cloudflareTunnelMode,
        cloudflarePublishLocalOriginUrl = cloudflare.cloudflarePublishLocalOriginUrl,
        cloudflareCredentialsRef = cloudflare.cloudflareCredentialsRef,
        chainEntry = chain.entryHop.config,
        chainEntryServer = chain.entryHop.server,
        chainEntryPort = chain.entryHop.serverPort,
        chainEntryServerName = chain.entryHop.serverName,
        chainEntryPublicKey = chain.entryHop.publicKey,
        chainEntryShortId = chain.entryHop.shortId,
        chainEntryProfileId = chain.entryHop.profileId,
        chainExit = chain.exitHop.config,
        chainExitServer = chain.exitHop.server,
        chainExitPort = chain.exitHop.serverPort,
        chainExitServerName = chain.exitHop.serverName,
        chainExitPublicKey = chain.exitHop.publicKey,
        chainExitShortId = chain.exitHop.shortId,
        chainExitProfileId = chain.exitHop.profileId,
        chainHops = chain.wireChainHops(),
        masqueUrl = masque.masqueUrl,
        masqueTcpProtocol = masque.masqueTcpProtocol,
        masqueUseHttp2Fallback = masque.masqueUseHttp2Fallback,
        masqueCloudflareGeohashEnabled = masque.masqueCloudflareGeohashEnabled,
        tuicZeroRtt = tuic.tuicZeroRtt,
        tuicCongestionControl = tuic.tuicCongestionControl,
        tuicRootCertificatePem = tuic.tuicRootCertificatePem,
        masqueRootCertificatePem = masque.masqueRootCertificatePem,
        tuicAlpn = tuic.tuicAlpn,
        shadowTlsInnerProfileId = shadowTls.shadowTlsInnerProfileId,
        shadowTlsInner = shadowTls.shadowTlsInner,
        trojanRootCertificatePem = trojan.trojanRootCertificatePem,
        naivePath = pluggableTransport.naivePath,
        mieruUsername = mieru.mieruUsername,
        mieruPassword = mieru.mieruPassword,
        mieruProtocol = mieru.mieruProtocol,
        mieruMultiplexing = mieru.mieruMultiplexing,
        mieruMtu = mieru.mieruMtu,
        mieruHandshakeMode = nativeMieruHandshakeMode(mieru.mieruHandshakeMode),
        mieruTrafficPattern = mieru.mieruTrafficPattern?.effective(),
        sshHost = ssh.sshHost,
        sshPort = ssh.sshPort,
        sshUsername = ssh.sshUsername,
        sshAuthType = ssh.sshAuthType,
        sshPassword = ssh.sshPassword,
        sshPrivateKey = ssh.sshPrivateKey,
        sshPrivateKeyPassphrase = ssh.sshPrivateKeyPassphrase,
        sshHostKeyFingerprint = ssh.sshHostKeyFingerprint,
        sshStrictHostKey = ssh.sshStrictHostKey,
        ptBridgeLine = pluggableTransport.ptBridgeLine,
        ptWebTunnelUrl = pluggableTransport.ptWebTunnelUrl,
        ptSnowflakeBrokerUrl = pluggableTransport.ptSnowflakeBrokerUrl,
        ptSnowflakeFrontDomain = pluggableTransport.ptSnowflakeFrontDomain,
        torStateDir = tor.torStateDir,
        torCacheDir = tor.torCacheDir,
        torBridgeLines = tor.torBridgeLines,
        torTransports = tor.torTransports,
        localSocksHost = common.localSocksHost,
        localSocksPort = common.localSocksPort,
        localSocksUsername = common.localSocksCredentials?.username,
        localSocksPassword = common.localSocksCredentials?.password,
        udpEnabled = common.udpEnabled,
        tcpFallbackEnabled = common.tcpFallbackEnabled,
        quicBindLowPort = common.quicBindLowPort,
        quicMigrateAfterHandshake = common.quicMigrateAfterHandshake,
        vlessUuid = vless.vlessUuid,
        chainEntryUuid = chain.entryHop.uuid,
        chainExitUuid = chain.exitHop.uuid,
        tuicUuid = tuic.tuicUuid,
        tuicPassword = tuic.tuicPassword,
        shadowTlsPassword = shadowTls.shadowTlsPassword,
        trojanPassword = trojan.trojanPassword,
        shadowsocksMethod = shadowsocks.shadowsocksMethod,
        shadowsocksPassword = shadowsocks.shadowsocksPassword,
        naiveUsername = pluggableTransport.naiveUsername,
        naivePassword = pluggableTransport.naivePassword,
        naiveExtraHeaders = pluggableTransport.naiveExtraHeaders,
        naiveServerPinSha256 = pluggableTransport.naiveServerPinSha256,
        naiveQuic = pluggableTransport.naiveQuic,
        naiveConcurrency = pluggableTransport.naiveConcurrency,
        tlsFingerprintProfile = common.tlsFingerprintProfile,
        masqueAuthMode = masque.masqueAuthMode,
        masqueAuthToken = masque.masqueAuthToken,
        masqueClientCertificateChainPem = masque.masqueClientCertificateChainPem,
        masqueClientPrivateKeyPem = masque.masqueClientPrivateKeyPem,
        masqueCloudflareGeohashHeader = masque.masqueCloudflareGeohashHeader,
        masquePrivacyPassProviderUrl = masque.masquePrivacyPassProviderUrl,
        masquePrivacyPassProviderAuthToken = masque.masquePrivacyPassProviderAuthToken,
        cloudflareTunnelToken = cloudflare.cloudflareTunnelToken,
        cloudflareTunnelCredentialsJson = cloudflare.cloudflareTunnelCredentialsJson,
        finalmask = finalmask,
    ).withHysteria2(hysteria2).withXhttp(vless).withAppsScript(appsScript).withAnyTls(anyTls).withSnell(snell)

private fun ResolvedRipDpiRelayConfig.withSnell(section: RelaySnellSection): ResolvedRipDpiRelayConfig =
    copy(snellPsk = section.snellPsk, snellUserKey = section.snellUserKey)

private fun ResolvedRipDpiRelayConfig.withAnyTls(section: RelayAnyTlsSection): ResolvedRipDpiRelayConfig =
    copy(
        anyTlsPassword = section.anyTlsPassword,
        anyTlsPinSha256 = section.anyTlsPinSha256,
    )

private fun ResolvedRipDpiRelayConfig.withAppsScript(section: RelayAppsScriptSection): ResolvedRipDpiRelayConfig =
    copy(
        appsScriptScriptIds = section.appsScriptScriptIds,
        appsScriptGoogleIp = section.appsScriptGoogleIp,
        appsScriptFrontDomain = section.appsScriptFrontDomain,
        appsScriptSniHosts = section.appsScriptSniHosts,
        appsScriptVerifySsl = section.appsScriptVerifySsl,
        appsScriptParallelRelay = section.appsScriptParallelRelay,
        appsScriptDirectHosts = section.appsScriptDirectHosts,
        appsScriptAuthKey = section.appsScriptAuthKey,
        appsScriptDataDir = section.appsScriptDataDir,
    )

private fun ResolvedRipDpiRelayConfig.withHysteria2(section: RelayHysteria2Section): ResolvedRipDpiRelayConfig =
    copy(
        hysteriaPassword = section.hysteriaPassword,
        hysteriaSalamanderKey = section.hysteriaSalamanderKey,
        hysteriaInsecure = section.hysteriaInsecure,
        hysteriaPinSha256 = section.hysteriaPinSha256,
        hysteriaPortHopPorts = section.hysteriaPortHopPorts,
        hysteriaPortHopIntervalMillis = section.hysteriaPortHopIntervalMillis,
        hysteriaPortHopIntervalMaxMillis = section.hysteriaPortHopIntervalMaxMillis,
        hysteriaRealmId = section.hysteriaRealmId,
        hysteriaRealmServerUrl = section.hysteriaRealmServerUrl,
        hysteriaRealmToken = section.hysteriaRealmToken,
        hysteriaRealmStunServers = section.hysteriaRealmStunServers,
    )

private fun ResolvedRipDpiRelayConfig.withXhttp(section: RelayVlessSection): ResolvedRipDpiRelayConfig =
    copy(
        xhttpPath = section.xhttpPath,
        xhttpHost = section.xhttpHost,
        xhttpMode = section.xhttpMode,
        xhttpMaxConnections = section.xhttpMaxConnections,
    )
