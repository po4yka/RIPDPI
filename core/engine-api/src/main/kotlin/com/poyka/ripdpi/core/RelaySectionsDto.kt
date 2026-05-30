package com.poyka.ripdpi.core

// === Section models ======================================================
//
// Each model groups one concern's slice of the flat [ResolvedRipDpiRelayConfig]
// field set. Field names mirror the wire DTO 1:1 so [toSections] /
// [RelayConfigSections.toResolvedConfig] are a flat, auditable mapping with no
// renaming. They are a construction- and inspection-time structuring aid; only
// [ResolvedRipDpiRelayConfig] is serialized onto the JNI wire.

/** Listener, transport-agnostic identity, and shared QUIC / TLS knobs. */
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
    val udpEnabled: Boolean,
    val tcpFallbackEnabled: Boolean,
    val quicBindLowPort: Boolean,
    val quicMigrateAfterHandshake: Boolean,
    val tlsFingerprintProfile: String,
)

/** VLESS Reality identity and transport (`reality_tcp` / `xhttp`). */
data class RelayVlessSection(
    val realityPublicKey: String,
    val realityShortId: String,
    val vlessTransport: String,
    val xhttpPath: String,
    val xhttpHost: String,
    val vlessUuid: String?,
)

/**
 * Chain-relay hops as an ordered, bounded list ([RelayChainMinHops] ..
 * [RelayChainMaxHops]). Hop 0 is the entry, the last hop is the exit; today the
 * list is always length 2, matching the flat wire DTO's `chainEntry*` /
 * `chainExit*` field pair, but the model is the seam through which N-hop runtime
 * composition lands in the next task.
 *
 * The constructor enforces the bound and raises [RelayChainHopCountException] on
 * an out-of-range count — there is no silent truncation. The `off`/non-chain
 * placeholder section (a config that is not a chain relay) is the one exemption:
 * an empty hop list is allowed so a default [ResolvedRipDpiRelayConfig] can still
 * be regrouped into sections; only chain-relay configs ever carry a non-empty
 * list, and those always satisfy the bound.
 */
data class RelayChainSection(
    val hops: List<ResolvedChainRelayHopRef>,
) {
    init {
        if (hops.isNotEmpty() && (hops.size < RelayChainMinHops || hops.size > RelayChainMaxHops)) {
            throw RelayChainHopCountException(hops.size)
        }
    }

    /** The entry (first) hop, or `null` when the section carries no hops. */
    val entry: ResolvedChainRelayHopRef?
        get() = hops.firstOrNull()

    /** The exit (last) hop, or `null` when the section carries no hops. */
    val exit: ResolvedChainRelayHopRef?
        get() = hops.lastOrNull()

    /** The entry hop, or the empty placeholder when no hops are present. */
    val entryHop: ResolvedChainRelayHopRef
        get() = entry ?: EmptyChainHopRef

    /** The exit hop, or the empty placeholder when no hops are present. */
    val exitHop: ResolvedChainRelayHopRef
        get() = exit ?: EmptyChainHopRef
}

/** Empty (no-chain) placeholder hop for non-chain-relay configs. */
private val EmptyChainHopRef =
    ResolvedChainRelayHopRef(
        config = null,
        server = "",
        serverPort = 0,
        serverName = "",
        publicKey = "",
        shortId = "",
        profileId = "",
        uuid = null,
    )

/** MASQUE endpoint, auth, and Privacy Pass fields. */
data class RelayMasqueSection(
    val masqueUrl: String,
    val masqueUseHttp2Fallback: Boolean,
    val masqueCloudflareGeohashEnabled: Boolean,
    val masqueAuthMode: String?,
    val masqueAuthToken: String?,
    val masqueClientCertificateChainPem: String?,
    val masqueClientPrivateKeyPem: String?,
    val masqueCloudflareGeohashHeader: String?,
    val masquePrivacyPassProviderUrl: String?,
    val masquePrivacyPassProviderAuthToken: String?,
)

/** TUIC v5 transport tuning and credentials. */
data class RelayTuicSection(
    val tuicZeroRtt: Boolean,
    val tuicCongestionControl: String,
    val tuicUuid: String?,
    val tuicPassword: String?,
)

/** ShadowTLS v3 inner-profile reference, resolved inner config, and credential. */
data class RelayShadowTlsSection(
    val shadowTlsInnerProfileId: String,
    val shadowTlsInner: ResolvedShadowTlsInnerRelayConfig?,
    val shadowTlsPassword: String?,
)

/** Trojan TLS root pin and credential. */
data class RelayTrojanSection(
    val trojanPassword: String?,
    val trojanRootCertificatePem: String?,
)

/** Shadowsocks method and credential. */
data class RelayShadowsocksSection(
    val shadowsocksMethod: String?,
    val shadowsocksPassword: String?,
)

/** Hysteria2 credentials. */
data class RelayHysteria2Section(
    val hysteriaPassword: String?,
    val hysteriaSalamanderKey: String?,
)

/** VMess (legacy outbound) endpoint, cipher, transport, and UUID credential. */
data class RelayVmessSection(
    val vmessServer: String,
    val vmessPort: Int,
    val vmessUuid: String?,
    val vmessSecurity: String,
    val vmessTransport: String,
    val vmessWsPath: String?,
    val vmessWsHost: String?,
    val vmessGrpcService: String?,
    val vmessH2Path: String?,
    val vmessH2Host: String?,
)

/** Trojan-Go (legacy outbound) endpoint, password credential, SNI/WS overrides, mux, and inner cipher. */
data class RelayTrojanGoSection(
    val trojanGoServer: String,
    val trojanGoPort: Int,
    val trojanGoPassword: String?,
    val trojanGoSni: String?,
    val trojanGoWsPath: String?,
    val trojanGoWsHost: String?,
    val trojanGoMux: String,
    val trojanGoInnerCipher: String,
)

/** Mieru outbound endpoint, username/password credentials, transport protocol, multiplexing, and MTU. */
data class RelayMieruSection(
    val mieruServer: String,
    val mieruPort: Int,
    val mieruUsername: String?,
    val mieruPassword: String?,
    val mieruProtocol: String,
    val mieruMultiplexing: String,
    val mieruMtu: Int,
)

/**
 * Hysteria v1 (legacy) outbound endpoint, auth-payload/obfuscation credentials,
 * auth-type encoding, transport protocol, up/down bandwidth, and optional
 * SNI/ALPN.
 */
data class RelayHysteriaV1Section(
    val hysteriaV1Server: String,
    val hysteriaV1Port: Int,
    val hysteriaV1AuthType: String,
    val hysteriaV1AuthPayload: String?,
    val hysteriaV1Obfuscation: String?,
    val hysteriaV1Protocol: String,
    val hysteriaV1UpMbps: Int,
    val hysteriaV1DownMbps: Int,
    val hysteriaV1Sni: String?,
    val hysteriaV1Alpn: String?,
)

/**
 * SSH outbound endpoint, auth selector, password/private-key/passphrase
 * credentials, and the host-key TOFU fields (pinned fingerprint plus the
 * strict-host-key toggle). The endpoint reuses the common relay server/port;
 * [sshAuthType] selects whether [sshPassword] or [sshPrivateKey] is required.
 */
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

/** AnyTLS credential. */
data class RelayAnyTlsSection(
    val anyTlsPassword: String?,
)

/** Subprocess relay fields: NaiveProxy plus obfs4 / WebTunnel / Snowflake. */
data class RelayPluggableTransportSection(
    val naivePath: String,
    val naiveUsername: String?,
    val naivePassword: String?,
    val ptBridgeLine: String,
    val ptWebTunnelUrl: String,
    val ptSnowflakeBrokerUrl: String,
    val ptSnowflakeFrontDomain: String,
)

/** Arti Tor state/cache directories and managed pluggable transport config. */
data class RelayTorSection(
    val torStateDir: String,
    val torCacheDir: String,
    val torBridgeLines: List<String>,
    val torTransports: List<ResolvedTorPluggableTransportConfig>,
)

/** Cloudflare Tunnel mode, publish origin, and credential references. */
data class RelayCloudflareSection(
    val cloudflareTunnelMode: String,
    val cloudflarePublishLocalOriginUrl: String,
    val cloudflareCredentialsRef: String,
    val cloudflareTunnelToken: String?,
    val cloudflareTunnelCredentialsJson: String?,
)

/** Google Apps Script relay routing fields. */
data class RelayAppsScriptSection(
    val appsScriptScriptIds: List<String>,
    val appsScriptGoogleIp: String,
    val appsScriptFrontDomain: String,
    val appsScriptSniHosts: List<String>,
    val appsScriptVerifySsl: Boolean,
    val appsScriptParallelRelay: Boolean,
    val appsScriptDirectHosts: List<String>,
    val appsScriptAuthKey: String?,
)
