package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val RelayKindOff = "off"
const val RelayKindVlessReality = "vless_reality"
const val RelayKindVless = "vless"
const val RelayKindHysteria2 = "hysteria2"
const val RelayKindChainRelay = "chain_relay"
const val RelayKindMasque = "masque"
const val RelayKindAnyTls = "anytls"
const val RelayKindCloudflareTunnel = "cloudflare_tunnel"
const val RelayKindTuicV5 = "tuic_v5"
const val RelayKindShadowTlsV3 = "shadowtls_v3"
const val RelayKindShadowsocks = "shadowsocks"
const val RelayKindTrojan = "trojan"
const val RelayKindNaiveProxy = "naiveproxy"
const val RelayKindTor = "tor"
const val RelayKindGoogleAppsScript = "google_apps_script"
const val RelayKindSnowflake = "snowflake"
const val RelayKindWebTunnel = "webtunnel"
const val RelayKindObfs4 = "obfs4"
const val RelayKindVmess = "vmess"
const val RelayVmessSecurityAes128Gcm = "aes-128-gcm"
const val RelayVmessSecurityChacha20Poly1305 = "chacha20-poly1305"
const val RelayVmessTransportTcp = "tcp"
const val RelayVmessTransportWs = "ws"
const val RelayVmessTransportH2 = "h2"
const val RelayVmessTransportGrpc = "grpc"
const val RelayKindTrojanGo = "trojan_go"
const val RelayTrojanGoMuxOff = "off"
const val RelayTrojanGoMuxSmuxV1 = "smux_v1"
const val RelayTrojanGoInnerCipherNone = "none"
const val RelayTrojanGoInnerCipherAes256Gcm = "aes-256-gcm"
const val RelayTrojanGoInnerCipherChacha20IetfPoly1305 = "chacha20-ietf-poly1305"
const val RelayKindMieru = "mieru"
const val RelayMieruProtocolTcp = "tcp"
const val RelayMieruProtocolUdp = "udp"
const val RelayMieruMultiplexingOff = "off"
const val RelayMieruMultiplexingLow = "low"
const val RelayMieruMultiplexingMiddle = "middle"
const val RelayMieruMultiplexingHigh = "high"
const val RelayMieruMtuMin = 1280
const val RelayMieruMtuMax = 1500
const val RelayMieruMtuDefault = 1400
const val RelayKindSsh = "ssh"
const val RelaySshAuthTypePassword: String = "password"
const val RelaySshAuthTypePrivateKey = "private_key"
const val RelayKindHysteriaV1 = "hysteria_v1"
const val RelayHysteriaV1AuthTypeString = "string"
const val RelayHysteriaV1AuthTypeBase64 = "base64"
const val RelayHysteriaV1ProtocolUdp = "udp"
const val RelayHysteriaV1ProtocolWechatVideo = "wechat-video"
const val RelayHysteriaV1ProtocolFaketcp = "faketcp"
const val RelayHysteriaV1UpMbpsDefault = 10
const val RelayHysteriaV1DownMbpsDefault = 50
const val RelayVlessTransportRealityTcp = "reality_tcp"
const val RelayVlessTransportXhttp = "xhttp"
const val RelaySecurityLayerTls = "tls"
const val RelaySecurityLayerReality = "reality"
const val RelayMasqueAuthModeBearer = "bearer"
const val RelayMasqueAuthModePreshared = "preshared"
const val RelayMasqueAuthModePrivacyPass = "privacy_pass"
const val RelayMasqueAuthModeCloudflareMtls = "cloudflare_mtls"
const val RelayCloudflareTunnelModeConsumeExisting = "consume_existing"
const val RelayCloudflareTunnelModePublishLocalOrigin = "publish_local_origin"
const val RelayFinalmaskTypeOff = "off"
const val RelayFinalmaskTypeHeaderCustom = "header_custom"
const val RelayFinalmaskTypeSudoku = "sudoku"
const val RelayFinalmaskTypeFragment = "fragment"
const val RelayFinalmaskTypeNoise = "noise"
const val RelayCongestionControlBbr = "bbr"
const val RelayCongestionControlCubic = "cubic"
const val DefaultRelayProfileId = "default"
const val DefaultRelayLocalSocksHost = "127.0.0.1"
const val DefaultRelayLocalSocksPort = 11980
const val DefaultRelayAppsScriptVerifySsl = true
const val DefaultSnowflakeBrokerUrl = "https://snowflake-broker.torproject.net/"
const val DefaultSnowflakeFrontDomain = "cdn.sstatic.net"

fun normalizeRelayKind(value: String): String =
    when (value.trim().lowercase()) {
        RelayKindVlessReality -> RelayKindVlessReality
        RelayKindVless -> RelayKindVless
        RelayKindHysteria2 -> RelayKindHysteria2
        RelayKindChainRelay -> RelayKindChainRelay
        RelayKindMasque -> RelayKindMasque
        RelayKindAnyTls -> RelayKindAnyTls
        RelayKindCloudflareTunnel -> RelayKindCloudflareTunnel
        RelayKindTuicV5 -> RelayKindTuicV5
        RelayKindShadowTlsV3 -> RelayKindShadowTlsV3
        RelayKindShadowsocks -> RelayKindShadowsocks
        RelayKindTrojan -> RelayKindTrojan
        RelayKindNaiveProxy -> RelayKindNaiveProxy
        RelayKindTor -> RelayKindTor
        RelayKindGoogleAppsScript -> RelayKindGoogleAppsScript
        RelayKindSnowflake -> RelayKindSnowflake
        RelayKindWebTunnel -> RelayKindWebTunnel
        RelayKindObfs4 -> RelayKindObfs4
        RelayKindVmess -> RelayKindVmess
        RelayKindTrojanGo -> RelayKindTrojanGo
        RelayKindMieru -> RelayKindMieru
        RelayKindSsh -> RelayKindSsh
        RelayKindHysteriaV1 -> RelayKindHysteriaV1
        else -> RelayKindOff
    }

/**
 * Normalizes the VMess cipher. VMess in RIPDPI is a legacy outbound; only the
 * two AEAD ciphers the native `ripdpi-vmess` backend accepts are valid. Any
 * other value (including the deprecated `auto` / `none` / `aes-128-cfb`) falls
 * back to [RelayVmessSecurityAes128Gcm].
 */
fun normalizeRelayVmessSecurity(value: String?): String =
    when (value?.trim()?.lowercase()) {
        RelayVmessSecurityChacha20Poly1305 -> RelayVmessSecurityChacha20Poly1305
        else -> RelayVmessSecurityAes128Gcm
    }

/**
 * Normalizes the VMess transport. The native backend accepts `tcp`, `ws`, `h2`,
 * and `grpc`; anything else falls back to [RelayVmessTransportTcp].
 */
fun normalizeRelayVmessTransport(value: String?): String =
    when (value?.trim()?.lowercase()) {
        RelayVmessTransportWs -> RelayVmessTransportWs
        RelayVmessTransportH2 -> RelayVmessTransportH2
        RelayVmessTransportGrpc -> RelayVmessTransportGrpc
        else -> RelayVmessTransportTcp
    }

/**
 * Normalizes the Trojan-Go multiplexing mode. The native `ripdpi-trojan-go`
 * backend accepts only `off` and `smux_v1`; anything else (including a blank
 * value) falls back to [RelayTrojanGoMuxOff] so the emitted wire token is the
 * canonical `off`, never an empty string.
 */
fun normalizeRelayTrojanGoMux(value: String?): String =
    when (value?.trim()?.lowercase()) {
        RelayTrojanGoMuxSmuxV1 -> RelayTrojanGoMuxSmuxV1
        else -> RelayTrojanGoMuxOff
    }

/**
 * Normalizes the optional Trojan-Go inner cipher. A blank value (or `none`)
 * means the plain Trojan-Go inner protocol and is emitted as `none`; the two
 * Shadowsocks-AEAD ciphers (`aes-256-gcm`, `chacha20-ietf-poly1305`) pass
 * through. Any other token falls back to [RelayTrojanGoInnerCipherNone].
 */
fun normalizeRelayTrojanGoInnerCipher(value: String?): String =
    when (value?.trim()?.lowercase()) {
        RelayTrojanGoInnerCipherAes256Gcm -> RelayTrojanGoInnerCipherAes256Gcm
        RelayTrojanGoInnerCipherChacha20IetfPoly1305 -> RelayTrojanGoInnerCipherChacha20IetfPoly1305
        else -> RelayTrojanGoInnerCipherNone
    }

/**
 * Normalizes the Mieru transport protocol. The native `ripdpi-mieru` backend
 * accepts only `tcp` and `udp`; anything else (including a blank value) falls
 * back to [RelayMieruProtocolTcp] so the emitted wire token is the canonical
 * `tcp`, never an empty string.
 */
fun normalizeRelayMieruProtocol(value: String?): String =
    when (value?.trim()?.lowercase()) {
        RelayMieruProtocolUdp -> RelayMieruProtocolUdp
        else -> RelayMieruProtocolTcp
    }

/**
 * Normalizes the Mieru multiplexing level. The native `ripdpi-mieru` backend
 * accepts `off`, `low`, `middle`, and `high`; anything else (including a blank
 * value) falls back to [RelayMieruMultiplexingMiddle], the Mieru default.
 */
fun normalizeRelayMieruMultiplexing(value: String?): String =
    when (value?.trim()?.lowercase()) {
        RelayMieruMultiplexingOff -> RelayMieruMultiplexingOff
        RelayMieruMultiplexingLow -> RelayMieruMultiplexingLow
        RelayMieruMultiplexingHigh -> RelayMieruMultiplexingHigh
        else -> RelayMieruMultiplexingMiddle
    }

/**
 * Normalizes the Mieru MTU. A value inside `1280..1500` passes through; any
 * out-of-range or non-positive value falls back to [RelayMieruMtuDefault].
 */
fun normalizeRelayMieruMtu(value: Int): Int =
    if (value in RelayMieruMtuMin..RelayMieruMtuMax) value else RelayMieruMtuDefault

/**
 * Normalizes the SSH outbound auth-type selector. The native `ripdpi-ssh`
 * backend authenticates either with a password or a private key; anything else
 * (including a blank value) falls back to [RelaySshAuthTypePassword], the
 * canonical default, never an empty string. The selected value decides which
 * credential field (`ssh_password` vs `ssh_private_key`) is required.
 */
fun normalizeRelaySshAuthType(value: String?): String =
    when (value?.trim()?.lowercase()) {
        RelaySshAuthTypePrivateKey -> RelaySshAuthTypePrivateKey
        else -> RelaySshAuthTypePassword
    }

/**
 * Normalizes the Hysteria v1 auth-type encoding. The native `ripdpi-hysteria-v1`
 * backend accepts only `string` and `base64`; anything else (including a blank
 * value) falls back to [RelayHysteriaV1AuthTypeString], the canonical default,
 * never an empty string.
 */
fun normalizeRelayHysteriaV1AuthType(value: String?): String =
    when (value?.trim()?.lowercase()) {
        RelayHysteriaV1AuthTypeBase64 -> RelayHysteriaV1AuthTypeBase64
        else -> RelayHysteriaV1AuthTypeString
    }

/**
 * Normalizes the Hysteria v1 transport protocol. The native `ripdpi-hysteria-v1`
 * backend accepts `udp`, `wechat-video`, and `faketcp`; anything else (including
 * a blank value) falls back to [RelayHysteriaV1ProtocolUdp], the Hysteria v1
 * default.
 */
fun normalizeRelayHysteriaV1Protocol(value: String?): String =
    when (value?.trim()?.lowercase()) {
        RelayHysteriaV1ProtocolWechatVideo -> RelayHysteriaV1ProtocolWechatVideo
        RelayHysteriaV1ProtocolFaketcp -> RelayHysteriaV1ProtocolFaketcp
        else -> RelayHysteriaV1ProtocolUdp
    }

/**
 * Normalizes a Hysteria v1 bandwidth knob (`up_mbps` / `down_mbps`). The native
 * backend requires both to be strictly positive; a non-positive value falls back
 * to [fallback], which is the per-direction default.
 */
fun normalizeRelayHysteriaV1Mbps(
    value: Int,
    fallback: Int,
): Int = if (value > 0) value else fallback

fun normalizeRelayStringList(values: Iterable<String>): List<String> =
    values
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()

fun normalizeRelayVlessTransport(
    value: String,
    relayKind: String? = null,
): String =
    when {
        normalizeRelayKind(relayKind.orEmpty()) == RelayKindCloudflareTunnel -> RelayVlessTransportXhttp
        value.trim().lowercase() == RelayVlessTransportXhttp -> RelayVlessTransportXhttp
        else -> RelayVlessTransportRealityTcp
    }

/**
 * Normalizes the relay security layer, orthogonal to the transport.
 *
 * The explicit `tls` / `reality` values win when present. Otherwise the kind
 * hint disambiguates: the plain [RelayKindVless] kind implies [RelaySecurityLayerTls],
 * every other kind (notably [RelayKindVlessReality]) implies
 * [RelaySecurityLayerReality]. The fallback is [RelaySecurityLayerReality] so
 * that records and settings written before this field existed keep their
 * legacy `vless_reality` default.
 */
fun normalizeRelaySecurityLayer(
    value: String,
    relayKind: String? = null,
): String =
    when (value.trim().lowercase()) {
        RelaySecurityLayerTls -> {
            RelaySecurityLayerTls
        }

        RelaySecurityLayerReality -> {
            RelaySecurityLayerReality
        }

        else -> {
            when (normalizeRelayKind(relayKind.orEmpty())) {
                RelayKindVless -> RelaySecurityLayerTls
                else -> RelaySecurityLayerReality
            }
        }
    }

fun normalizeRelayMasqueAuthMode(value: String?): String? =
    when (value?.trim()?.lowercase()) {
        RelayMasqueAuthModeBearer,
        "token",
        -> RelayMasqueAuthModeBearer

        RelayMasqueAuthModePreshared -> RelayMasqueAuthModePreshared

        RelayMasqueAuthModePrivacyPass -> RelayMasqueAuthModePrivacyPass

        RelayMasqueAuthModeCloudflareMtls -> RelayMasqueAuthModeCloudflareMtls

        null,
        "",
        -> null

        else -> null
    }

fun normalizeRelayCongestionControl(value: String?): String =
    when (value?.trim()?.lowercase()) {
        RelayCongestionControlCubic -> RelayCongestionControlCubic
        else -> RelayCongestionControlBbr
    }

fun normalizeRelayCloudflareTunnelMode(value: String?): String =
    when (value?.trim()?.lowercase()) {
        RelayCloudflareTunnelModePublishLocalOrigin -> RelayCloudflareTunnelModePublishLocalOrigin
        else -> RelayCloudflareTunnelModeConsumeExisting
    }

fun normalizeRelayFinalmaskType(value: String?): String =
    when (value?.trim()?.lowercase()) {
        RelayFinalmaskTypeHeaderCustom -> RelayFinalmaskTypeHeaderCustom
        RelayFinalmaskTypeSudoku -> RelayFinalmaskTypeSudoku
        RelayFinalmaskTypeFragment -> RelayFinalmaskTypeFragment
        RelayFinalmaskTypeNoise -> RelayFinalmaskTypeNoise
        else -> RelayFinalmaskTypeOff
    }

data class RelayFinalmaskModel(
    val type: String = RelayFinalmaskTypeOff,
    val headerHex: String = "",
    val trailerHex: String = "",
    val randRange: String = "",
    val sudokuSeed: String = "",
    val fragmentPackets: Int = 0,
    val fragmentMinBytes: Int = 0,
    val fragmentMaxBytes: Int = 0,
)

data class RelayProfileModel(
    val id: String = DefaultRelayProfileId,
    val kind: String = RelayKindOff,
    val presetId: String = "",
    val outboundBindIp: String = "",
    val server: String = "",
    val serverPort: Int = 443,
    val serverName: String = "",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val vlessTransport: String = RelayVlessTransportRealityTcp,
    val xhttpPath: String = "",
    val xhttpHost: String = "",
    val cloudflareTunnelMode: String = RelayCloudflareTunnelModeConsumeExisting,
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
    // Ordered intermediate chain-relay hop profile IDs (positions strictly
    // between entry and exit) for N-hop (3..4) chains. Empty for two-hop chains.
    val chainMiddleProfileIds: List<String> = emptyList(),
    val masqueUrl: String = "",
    val masqueUseHttp2Fallback: Boolean = true,
    val masqueCloudflareGeohashEnabled: Boolean = false,
    val tuicZeroRtt: Boolean = false,
    val tuicCongestionControl: String = RelayCongestionControlBbr,
    val shadowTlsInnerProfileId: String = "",
    val naivePath: String = "",
    val vmessSecurity: String = RelayVmessSecurityAes128Gcm,
    val vmessTransport: String = RelayVmessTransportTcp,
    val vmessWsPath: String = "",
    val vmessWsHost: String = "",
    val vmessGrpcService: String = "",
    val vmessH2Path: String = "",
    val vmessH2Host: String = "",
    val trojanGoSni: String = "",
    val trojanGoWsPath: String = "",
    val trojanGoWsHost: String = "",
    val trojanGoMux: String = RelayTrojanGoMuxOff,
    val trojanGoInnerCipher: String = RelayTrojanGoInnerCipherNone,
    val mieruProtocol: String = RelayMieruProtocolTcp,
    val mieruMultiplexing: String = RelayMieruMultiplexingMiddle,
    val mieruMtu: Int = RelayMieruMtuDefault,
    val sshAuthType: String = RelaySshAuthTypePassword,
    val sshHostKeyFingerprint: String = "",
    val sshStrictHostKey: Boolean = false,
    val hysteriaV1AuthType: String = RelayHysteriaV1AuthTypeString,
    val hysteriaV1Protocol: String = RelayHysteriaV1ProtocolUdp,
    val hysteriaV1UpMbps: Int = RelayHysteriaV1UpMbpsDefault,
    val hysteriaV1DownMbps: Int = RelayHysteriaV1DownMbpsDefault,
    val hysteriaV1Sni: String = "",
    val hysteriaV1Alpn: String = "",
    val appsScriptScriptIds: List<String> = emptyList(),
    val appsScriptGoogleIp: String = "",
    val appsScriptFrontDomain: String = "",
    val appsScriptSniHosts: List<String> = emptyList(),
    val appsScriptVerifySsl: Boolean = DefaultRelayAppsScriptVerifySsl,
    val appsScriptParallelRelay: Boolean = false,
    val appsScriptDirectHosts: List<String> = emptyList(),
    val ptBridgeLine: String = "",
    val ptWebTunnelUrl: String = "",
    val ptSnowflakeBrokerUrl: String = DefaultSnowflakeBrokerUrl,
    val ptSnowflakeFrontDomain: String = DefaultSnowflakeFrontDomain,
    val localSocksHost: String = DefaultRelayLocalSocksHost,
    val localSocksPort: Int = DefaultRelayLocalSocksPort,
    val udpEnabled: Boolean = false,
    val tcpFallbackEnabled: Boolean = true,
    val finalmask: RelayFinalmaskModel = RelayFinalmaskModel(),
)

data class RelaySettingsModel(
    val enabled: Boolean = false,
    val kind: String = RelayKindOff,
    val profileId: String = DefaultRelayProfileId,
    val profile: RelayProfileModel = RelayProfileModel(),
)

fun AppSettings.toRelaySettingsModel(): RelaySettingsModel {
    val profileId = relayProfileId.ifBlank { DefaultRelayProfileId }
    val kind = normalizeRelayKind(relayKind)
    return RelaySettingsModel(
        enabled = relayEnabled && kind != RelayKindOff,
        kind = kind,
        profileId = profileId,
        profile =
            RelayProfileModel(
                id = profileId,
                kind = kind,
                presetId = "",
                outboundBindIp = relayOutboundBindIp,
                server = relayServer,
                serverPort = relayServerPort.takeIf { it > 0 } ?: 443,
                serverName = relayServerName,
                realityPublicKey = relayRealityPublicKey,
                realityShortId = relayRealityShortId,
                vlessTransport = normalizeRelayVlessTransport(relayVlessTransport, kind),
                xhttpPath = relayXhttpPath,
                xhttpHost = relayXhttpHost,
                cloudflareTunnelMode = normalizeRelayCloudflareTunnelMode(relayCloudflareTunnelMode),
                cloudflarePublishLocalOriginUrl = relayCloudflarePublishLocalOriginUrl,
                cloudflareCredentialsRef = relayCloudflareCredentialsRef,
                chainEntryServer = relayChainEntryServer,
                chainEntryPort = relayChainEntryPort.takeIf { it > 0 } ?: 443,
                chainEntryServerName = relayChainEntryServerName,
                chainEntryPublicKey = relayChainEntryPublicKey,
                chainEntryShortId = relayChainEntryShortId,
                chainEntryProfileId = relayChainEntryProfileId,
                chainExitServer = relayChainExitServer,
                chainExitPort = relayChainExitPort.takeIf { it > 0 } ?: 443,
                chainExitServerName = relayChainExitServerName,
                chainExitPublicKey = relayChainExitPublicKey,
                chainExitShortId = relayChainExitShortId,
                chainExitProfileId = relayChainExitProfileId,
                chainMiddleProfileIds = relayChainMiddleProfileIdsList.toList(),
                masqueUrl = relayMasqueUrl,
                masqueUseHttp2Fallback = relayMasqueUseHttp2Fallback,
                masqueCloudflareGeohashEnabled = relayMasqueCloudflareGeohashEnabled,
                tuicZeroRtt = relayTuicZeroRtt,
                tuicCongestionControl = normalizeRelayCongestionControl(relayTuicCongestionControl),
                shadowTlsInnerProfileId = relayShadowtlsInnerProfileId,
                naivePath = relayNaivePath,
                vmessSecurity = normalizeRelayVmessSecurity(relayVmessSecurity),
                vmessTransport = normalizeRelayVmessTransport(relayVmessTransport),
                vmessWsPath = relayVmessWsPath,
                vmessWsHost = relayVmessWsHost,
                vmessGrpcService = relayVmessGrpcService,
                vmessH2Path = relayVmessH2Path,
                vmessH2Host = relayVmessH2Host,
                trojanGoSni = relayTrojanGoSni,
                trojanGoWsPath = relayTrojanGoWsPath,
                trojanGoWsHost = relayTrojanGoWsHost,
                trojanGoMux = normalizeRelayTrojanGoMux(relayTrojanGoMux),
                trojanGoInnerCipher = normalizeRelayTrojanGoInnerCipher(relayTrojanGoInnerCipher),
                mieruProtocol = normalizeRelayMieruProtocol(relayMieruProtocol),
                mieruMultiplexing = normalizeRelayMieruMultiplexing(relayMieruMultiplexing),
                mieruMtu = normalizeRelayMieruMtu(relayMieruMtu),
                sshAuthType = normalizeRelaySshAuthType(relaySshAuthType),
                sshHostKeyFingerprint = relaySshHostKeyFingerprint.trim(),
                sshStrictHostKey = relaySshStrictHostKey,
                hysteriaV1AuthType = normalizeRelayHysteriaV1AuthType(relayHysteriaV1AuthType),
                hysteriaV1Protocol = normalizeRelayHysteriaV1Protocol(relayHysteriaV1Protocol),
                hysteriaV1UpMbps = normalizeRelayHysteriaV1Mbps(relayHysteriaV1UpMbps, RelayHysteriaV1UpMbpsDefault),
                hysteriaV1DownMbps =
                    normalizeRelayHysteriaV1Mbps(
                        relayHysteriaV1DownMbps,
                        RelayHysteriaV1DownMbpsDefault,
                    ),
                hysteriaV1Sni = relayHysteriaV1Sni.trim(),
                hysteriaV1Alpn = relayHysteriaV1Alpn.trim(),
                appsScriptScriptIds = normalizeRelayStringList(relayAppsScriptScriptIdsList),
                appsScriptGoogleIp = relayAppsScriptGoogleIp.trim(),
                appsScriptFrontDomain = relayAppsScriptFrontDomain.trim(),
                appsScriptSniHosts = normalizeRelayStringList(relayAppsScriptSniHostsList),
                appsScriptVerifySsl = relayAppsScriptVerifySsl,
                appsScriptParallelRelay = relayAppsScriptParallelRelay,
                appsScriptDirectHosts = normalizeRelayStringList(relayAppsScriptDirectHostsList),
                ptBridgeLine = "",
                ptWebTunnelUrl = "",
                ptSnowflakeBrokerUrl = DefaultSnowflakeBrokerUrl,
                ptSnowflakeFrontDomain = DefaultSnowflakeFrontDomain,
                localSocksHost = relayLocalSocksHost.ifBlank { DefaultRelayLocalSocksHost },
                localSocksPort = relayLocalSocksPort.takeIf { it > 0 } ?: DefaultRelayLocalSocksPort,
                udpEnabled = relayUdpEnabled,
                tcpFallbackEnabled = relayTcpFallbackEnabled,
                finalmask =
                    RelayFinalmaskModel(
                        type = normalizeRelayFinalmaskType(relayFinalmaskType),
                        headerHex = relayFinalmaskHeaderHex,
                        trailerHex = relayFinalmaskTrailerHex,
                        randRange = relayFinalmaskRandRange,
                        sudokuSeed = relayFinalmaskSudokuSeed,
                        fragmentPackets = relayFinalmaskFragmentPackets,
                        fragmentMinBytes = relayFinalmaskFragmentMinBytes,
                        fragmentMaxBytes = relayFinalmaskFragmentMaxBytes,
                    ),
            ),
    )
}
