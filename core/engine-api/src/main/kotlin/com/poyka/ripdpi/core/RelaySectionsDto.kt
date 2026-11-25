package com.poyka.ripdpi.core

data class RelayCommonSection(
    val enabled: Boolean,
    val kind: String,
    val profileId: String,
    val outboundBindIp: String,
    val server: String,
    val serverPort: Int,
    val serverName: String,
    val localSocksHost: String,
    val localSocksPort: Int,
    val localSocksCredentials: LocalSocksCredentials? = null,
    val udpEnabled: Boolean,
    val tcpFallbackEnabled: Boolean,
    val quicBindLowPort: Boolean,
    val quicMigrateAfterHandshake: Boolean,
    val tlsFingerprintProfile: String,
    val socketProtection: RelaySocketProtection = RelaySocketProtection.Inactive,
)

data class RelayVlessSection(
    val realityPublicKey: String,
    val realityShortId: String,
    val vlessFlow: String,
    val vlessTransport: String,
    val xhttpPath: String,
    val xhttpHost: String,
    val xhttpMode: String,
    val xhttpMaxConnections: Int? = null,
    val vlessMuxProtocol: String,
    val vlessMuxMaxConcurrentStreams: Int,
    val vlessMuxPerConnectionKbps: Int,
    val vlessMuxPaddingMax: Int,
    val vlessUuid: String?,
)

data class RelayChainSection(
    val hops: List<ResolvedChainRelayHopRef>,
) {
    init {
        if (hops.isNotEmpty() && (hops.size < RelayChainMinHops || hops.size > RelayChainMaxHops)) {
            throw RelayChainHopCountException(hops.size)
        }
    }

    val entry: ResolvedChainRelayHopRef?
        get() = hops.firstOrNull()

    val exit: ResolvedChainRelayHopRef?
        get() = hops.lastOrNull()

    val entryHop: ResolvedChainRelayHopRef
        get() = entry ?: EmptyChainHopRef

    val exitHop: ResolvedChainRelayHopRef
        get() = exit ?: EmptyChainHopRef
}

private val EmptyChainHopRef =
    ResolvedChainRelayHopRef(
        config = null,
        server = "",
        serverPort = 0,
        serverName = "",
        publicKey = "",
        shortId = "",
        flow = com.poyka.ripdpi.data.RelayVlessFlowVision,
        xhttpMode = com.poyka.ripdpi.data.RelayXhttpModeAuto,
        profileId = "",
        uuid = null,
    )

data class RelayMasqueSection(
    val masqueUrl: String,
    val masqueTcpProtocol: String = "http2",
    val masqueUseHttp2Fallback: Boolean,
    val masqueCloudflareGeohashEnabled: Boolean,
    val masqueAuthMode: String?,
    val masqueAuthToken: String?,
    val masqueClientCertificateChainPem: String?,
    val masqueClientPrivateKeyPem: String?,
    val masqueCloudflareGeohashHeader: String?,
    val masquePrivacyPassProviderUrl: String?,
    val masquePrivacyPassProviderAuthToken: String?,
    val masqueRootCertificatePem: String? = null,
)

data class RelayTuicSection(
    val tuicZeroRtt: Boolean,
    val tuicCongestionControl: String,
    val tuicRootCertificatePem: String? = null,
    val tuicAlpn: List<String>? = null,
    val tuicUuid: String?,
    val tuicPassword: String?,
)

data class RelayShadowTlsSection(
    val shadowTlsInnerProfileId: String,
    val shadowTlsInner: ResolvedShadowTlsInnerRelayConfig?,
    val shadowTlsPassword: String?,
)

data class RelayTrojanSection(
    val trojanPassword: String?,
    val trojanRootCertificatePem: String?,
)

data class RelayShadowsocksSection(
    val shadowsocksMethod: String?,
    val shadowsocksPassword: String?,
)

data class RelayHysteria2Section(
    val hysteriaPassword: String?,
    val hysteriaSalamanderKey: String?,
    val hysteriaInsecure: Boolean = false,
    val hysteriaPinSha256: String? = null,
    val hysteriaPortHopPorts: String? = null,
    val hysteriaPortHopIntervalMillis: Long? = null,
    val hysteriaPortHopIntervalMaxMillis: Long? = null,
    val hysteriaRealmId: String? = null,
    val hysteriaRealmServerUrl: String? = null,
    val hysteriaRealmToken: String? = null,
    val hysteriaRealmStunServers: List<String> = emptyList(),
)

data class RelayMieruSection(
    val mieruUsername: String?,
    val mieruPassword: String?,
    val mieruProtocol: String,
    val mieruMultiplexing: String,
    val mieruMtu: Int,
    val mieruHandshakeMode: String? = null,
    val mieruTrafficPattern: com.poyka.ripdpi.data.MieruTrafficPattern? = null,
)

data class RelaySshSection(
    val sshHost: String,
    val sshPort: Int,
    val sshUsername: String?,
    val sshAuthType: String,
    val sshPassword: String?,
    val sshPrivateKey: String?,
    val sshPrivateKeyPassphrase: String?,
    val sshHostKeyFingerprint: String?,
    val sshStrictHostKey: Boolean,
)

data class RelaySnellSection(
    val snellPsk: String?,
    val snellUserKey: String?,
)

data class RelayAnyTlsSection(
    val anyTlsPassword: String?,
    val anyTlsPinSha256: String? = null,
)

data class RelayPluggableTransportSection(
    val naivePath: String,
    val naiveUsername: String?,
    val naivePassword: String?,
    val ptBridgeLine: String,
    val ptWebTunnelUrl: String,
    val ptSnowflakeBrokerUrl: String,
    val ptSnowflakeFrontDomain: String,
    val naiveExtraHeaders: String = "",
    val naiveServerPinSha256: String = "",
    val naiveQuic: Boolean = false,
    val naiveConcurrency: Int = 1,
)

data class RelayTorSection(
    val torStateDir: String,
    val torCacheDir: String,
    val torBridgeLines: List<String>,
    val torTransports: List<ResolvedTorPluggableTransportConfig>,
)

data class RelayCloudflareSection(
    val cloudflareTunnelMode: String,
    val cloudflarePublishLocalOriginUrl: String,
    val cloudflareCredentialsRef: String,
    val cloudflareTunnelToken: String?,
    val cloudflareTunnelCredentialsJson: String?,
)

data class RelayAppsScriptSection(
    val appsScriptScriptIds: List<String>,
    val appsScriptGoogleIp: String,
    val appsScriptFrontDomain: String,
    val appsScriptSniHosts: List<String>,
    val appsScriptVerifySsl: Boolean,
    val appsScriptParallelRelay: Boolean,
    val appsScriptDirectHosts: List<String>,
    val appsScriptAuthKey: String?,
    val appsScriptDataDir: String? = null,
)
