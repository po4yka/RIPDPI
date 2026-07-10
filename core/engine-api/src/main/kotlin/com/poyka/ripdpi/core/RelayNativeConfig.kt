package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.RelayCongestionControlBbr
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Runtime-owned policy for protecting relay carrier sockets from the app TUN. */
@Serializable
enum class RelaySocketProtection {
    /** Proxy mode or another runtime with no TUN routing loop risk. */
    @SerialName("inactive")
    Inactive,

    /** VPN mode: every non-loopback carrier socket must be protected or fail closed. */
    @SerialName("vpn_required")
    VpnRequired,
}

/**
 * Relay native runtime configuration — the payload [RipDpiRelay] encodes to
 * JSON and hands to `libripdpi-relay.so` (Rust crate `ripdpi-relay-android`).
 *
 * [ResolvedRipDpiRelayConfig] is the **wire DTO**: a flat `@Serializable`
 * object whose shape is a config contract with the Rust
 * `FlatResolvedRelayRuntimeConfig` deserializer. Its field set, names, defaults,
 * and `finalmask` / `shadowTlsInner` nested objects must not change without
 * updating the Rust side in the same commit — see
 * `docs/architecture/CONFIG_CONTRACTS.md`.
 *
 * The `Relay*Section` models below give that flat field set a concern-grouped
 * structure: relay-config construction assembles the sections, then
 * [RelayConfigSections.toResolvedConfig] flattens them into the wire DTO — the
 * `core:service` resolver builds every [ResolvedRipDpiRelayConfig] this way.
 * [toSections] is the lossless inverse, for code that inspects an existing
 * config. The section models never touch the wire — only
 * [ResolvedRipDpiRelayConfig] is serialized — so they carry no `@Serializable`
 * annotation and impose no JSON-compatibility constraint.
 */

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
    val realityPublicKey: String = "",
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
    val shadowTlsInnerProfileId: String = "",
    val shadowTlsInner: ResolvedShadowTlsInnerRelayConfig? = null,
    val trojanRootCertificatePem: String? = null,
    val anytlsRootCertificatePem: String? = null,
    val naivePath: String = "",
    val vlessUuid: String? = null,
    val hysteriaPassword: String? = null,
    val hysteriaSalamanderKey: String? = null,
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
    // Ordered N-hop chain list for 3- and 4-hop chains; the chainEntry/chainExit
    // resolved configs carry current 2-hop identity and the scalar fields above
    // remain their derived hop[0]/hop[last] mirror. See CONFIG_CONTRACTS.md §8.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val chainHops: List<ResolvedChainRelayHopConfig> = emptyList(),
    val masqueUrl: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val masqueTcpProtocol: String = "http2",
    val masqueUseHttp2Fallback: Boolean,
    val masqueCloudflareGeohashEnabled: Boolean = false,
    val tuicZeroRtt: Boolean = false,
    val tuicCongestionControl: String = RelayCongestionControlBbr,
    val shadowTlsInnerProfileId: String = "",
    val shadowTlsInner: ResolvedShadowTlsInnerRelayConfig? = null,
    val trojanRootCertificatePem: String? = null,
    val naivePath: String = "",
    val mieruServer: String = "",
    val mieruPort: Int = 0,
    val mieruUsername: String? = null,
    val mieruPassword: String? = null,
    val mieruProtocol: String = com.poyka.ripdpi.data.RelayMieruProtocolTcp,
    val mieruMultiplexing: String = com.poyka.ripdpi.data.RelayMieruMultiplexingMiddle,
    val mieruMtu: Int = com.poyka.ripdpi.data.RelayMieruMtuDefault,
    val sshHost: String = "",
    val sshPort: Int = 0,
    val sshUsername: String? = null,
    val sshAuthType: String = com.poyka.ripdpi.data.RelaySshAuthTypePassword,
    val sshPassword: String? = null,
    val sshPrivateKey: String? = null,
    val sshPrivateKeyPassphrase: String? = null,
    val sshHostKeyFingerprint: String? = null,
    val sshStrictHostKey: Boolean = false,
    val ptBridgeLine: String = "",
    val ptWebTunnelUrl: String = "",
    val ptSnowflakeBrokerUrl: String = "",
    val ptSnowflakeFrontDomain: String = "",
    val torStateDir: String = "",
    val torCacheDir: String = "",
    val torBridgeLines: List<String> = emptyList(),
    val torTransports: List<ResolvedTorPluggableTransportConfig> = emptyList(),
    val localSocksHost: String,
    val localSocksPort: Int,
    val udpEnabled: Boolean,
    val tcpFallbackEnabled: Boolean,
    val quicBindLowPort: Boolean = false,
    val quicMigrateAfterHandshake: Boolean = false,
    val vlessUuid: String? = null,
    val chainEntryUuid: String? = null,
    val chainExitUuid: String? = null,
    val hysteriaPassword: String? = null,
    val hysteriaSalamanderKey: String? = null,
    val hysteriaInsecure: Boolean = false,
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
    val appsScriptScriptIds: List<String> = emptyList(),
    val appsScriptGoogleIp: String = "",
    val appsScriptFrontDomain: String = "",
    val appsScriptSniHosts: List<String> = emptyList(),
    val appsScriptVerifySsl: Boolean = com.poyka.ripdpi.data.DefaultRelayAppsScriptVerifySsl,
    val appsScriptParallelRelay: Boolean = false,
    val appsScriptDirectHosts: List<String> = emptyList(),
    val appsScriptAuthKey: String? = null,
    val finalmask: ResolvedRelayFinalmaskConfig = ResolvedRelayFinalmaskConfig(),
    @Required
    val schemaVersion: Int = RelayNativeConfigSchemaVersion,
)

/**
 * Current relay native-config wire schema version. Every
 * [ResolvedRipDpiRelayConfig] must carry `schemaVersion`; missing and
 * non-current versions are rejected. Version 10 requires explicit top-level,
 * chain-hop, and ShadowTLS-inner TLS fingerprint fields and retires version 9.
 * Mirrors the Rust schema.
 */
const val RelayNativeConfigSchemaVersion: Int = 10

/** Minimum number of hops a chain-relay section may carry. */
const val RelayChainMinHops: Int = 2

/** Maximum number of hops a chain-relay section may carry. */
const val RelayChainMaxHops: Int = 4

/**
 * Typed validation failure raised when a chain-relay hop list falls outside the
 * `[RelayChainMinHops, RelayChainMaxHops]` bound. Carries the offending count so
 * the caller can surface a precise diagnostic — there is no silent truncation.
 */
class RelayChainHopCountException(
    val hopCount: Int,
    val minHops: Int = RelayChainMinHops,
    val maxHops: Int = RelayChainMaxHops,
) : IllegalArgumentException(
        "chain relay hop count $hopCount is out of range [$minHops, $maxHops]",
    )

/**
 * One ordered hop in a [RelayChainSection] hop list. Mirrors the flat
 * `chainEntry*` / `chainExit*` wire-DTO scalar group for a single position:
 * [config] is the optional resolved hop template, the remaining fields are the
 * inline / referenced-profile hop reference. Hop 0 is the entry, the last hop is
 * the exit; intermediate hops are reserved for N-hop runtime composition (the
 * next task) and today the list is always length 2.
 */
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

// Section models — the per-protocol `Relay*Section` data classes — live in
// `RelaySectionsDto.kt` (same package). The conversion functions below regroup
// the flat [ResolvedRipDpiRelayConfig] wire DTO into those sections and back.

/**
 * The complete [ResolvedRipDpiRelayConfig] field set regrouped into section
 * models. Convert back to the flat wire DTO with [toResolvedConfig].
 */
data class RelayConfigSections(
    val common: RelayCommonSection,
    val vless: RelayVlessSection,
    val chain: RelayChainSection,
    val masque: RelayMasqueSection,
    val tuic: RelayTuicSection,
    val shadowTls: RelayShadowTlsSection,
    val trojan: RelayTrojanSection,
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
        vlessUuid = vlessUuid,
    )

// A hop reference derived from a wire [ResolvedChainRelayHopConfig]: the rich
// resolved template is kept in [ResolvedChainRelayHopRef.config], and the scalar
// mirror fields are projected from it so the entry/exit derived slots and the
// in-process composition see a consistent view.
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

// Fold the flat wire DTO's chain fields into the ordered hop list. When the
// additive [ResolvedRipDpiRelayConfig.chainHops] list is populated it is the
// N-hop (3-/4-hop) source of truth and is used directly. Otherwise — v6 payloads
// and plain 2-hop chains — the legacy two-hop `chainEntry*` / `chainExit*`
// scalars are folded into a 2-element list (hop 0 = entry, hop 1 = exit), which
// is also the lossless v6 -> v7 migration.
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
    )

private fun ResolvedRipDpiRelayConfig.tuicSection(): RelayTuicSection =
    RelayTuicSection(
        tuicZeroRtt = tuicZeroRtt,
        tuicCongestionControl = tuicCongestionControl,
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
    )

private fun ResolvedRipDpiRelayConfig.mieruSection(): RelayMieruSection =
    RelayMieruSection(
        mieruServer = mieruServer,
        mieruPort = mieruPort,
        mieruUsername = mieruUsername,
        mieruPassword = mieruPassword,
        mieruProtocol = mieruProtocol,
        mieruMultiplexing = mieruMultiplexing,
        mieruMtu = mieruMtu,
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
    )

private fun ResolvedRipDpiRelayConfig.pluggableTransportSection(): RelayPluggableTransportSection =
    RelayPluggableTransportSection(
        naivePath = naivePath,
        naiveUsername = naiveUsername,
        naivePassword = naivePassword,
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
    )

/** Regroup this flat relay config into concern-scoped [RelayConfigSections]. */
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

// Prefer the ordered [ResolvedRipDpiRelayConfig.chainHops] list (the N-hop
// source of truth); fall back to the resolved two-hop entry/exit fold when it
// is empty. Scalar fields remain derived mirrors, not executable identity.
// Brace-free single
// expression: the empty-list fallback is a bound function reference, so this
// helper file's brace structure is unchanged from the v6 two-hop shape.
private fun ResolvedRipDpiRelayConfig.orderedChainHopRefs(): List<ResolvedChainRelayHopRef> =
    chainHops.map(ResolvedChainRelayHopConfig::toHopRef).ifEmpty(this::legacyTwoHopChainRefs)

private fun ResolvedRipDpiRelayConfig.chainSection(): RelayChainSection =
    RelayChainSection(hops = orderedChainHopRefs())

// The ordered hop list to carry over the wire. Emitted only for genuinely N-hop
// chains (3 or 4 hops); a plain 2-hop chain stays expressed by the required
// resolved entry/exit configs plus their derived scalar mirrors. Single
// brace-free expression to keep this helper file's brace structure unchanged.
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

/**
 * Flatten the section models back into the [ResolvedRipDpiRelayConfig] wire
 * DTO. Inverse of [toSections]: `config.toSections().toResolvedConfig()`
 * reproduces `config` exactly.
 *
 * The ordered chain-relay hop list is unfolded back into the flat two-hop wire
 * slots via `chain.entryHop` / `chain.exitHop`: hop 0 -> `chainEntry*`, last hop
 * -> `chainExit*` (inverse of the fold in `chainSection()`). Intermediate hops
 * (list lengths 3..4) are not yet expressible on the flat wire — N-hop runtime
 * composition lands in the next task, which owns the wire-shape extension; today
 * the list is always length 2.
 *
 * `@Suppress("LongMethod")`: a flat 1:1 field copy from the concern-scoped
 * sections back onto the wire DTO — every line is one field assignment, so the
 * length is structural, not complexity.
 */
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
        xhttpPath = vless.xhttpPath,
        xhttpHost = vless.xhttpHost,
        xhttpMode = vless.xhttpMode,
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
        // Carry the ordered list over the wire only for genuinely N-hop chains
        // (3 or 4 hops). A plain 2-hop chain stays fully expressed by the
        // entry/exit scalars above, keeping its wire shape byte-identical to v6.
        chainHops = chain.wireChainHops(),
        masqueUrl = masque.masqueUrl,
        masqueTcpProtocol = masque.masqueTcpProtocol,
        masqueUseHttp2Fallback = masque.masqueUseHttp2Fallback,
        masqueCloudflareGeohashEnabled = masque.masqueCloudflareGeohashEnabled,
        tuicZeroRtt = tuic.tuicZeroRtt,
        tuicCongestionControl = tuic.tuicCongestionControl,
        shadowTlsInnerProfileId = shadowTls.shadowTlsInnerProfileId,
        shadowTlsInner = shadowTls.shadowTlsInner,
        trojanRootCertificatePem = trojan.trojanRootCertificatePem,
        naivePath = pluggableTransport.naivePath,
        mieruServer = mieru.mieruServer,
        mieruPort = mieru.mieruPort,
        mieruUsername = mieru.mieruUsername,
        mieruPassword = mieru.mieruPassword,
        mieruProtocol = mieru.mieruProtocol,
        mieruMultiplexing = mieru.mieruMultiplexing,
        mieruMtu = mieru.mieruMtu,
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
        udpEnabled = common.udpEnabled,
        tcpFallbackEnabled = common.tcpFallbackEnabled,
        quicBindLowPort = common.quicBindLowPort,
        quicMigrateAfterHandshake = common.quicMigrateAfterHandshake,
        vlessUuid = vless.vlessUuid,
        chainEntryUuid = chain.entryHop.uuid,
        chainExitUuid = chain.exitHop.uuid,
        hysteriaPassword = hysteria2.hysteriaPassword,
        hysteriaSalamanderKey = hysteria2.hysteriaSalamanderKey,
        hysteriaInsecure = hysteria2.hysteriaInsecure,
        anyTlsPassword = anyTls.anyTlsPassword,
        tuicUuid = tuic.tuicUuid,
        tuicPassword = tuic.tuicPassword,
        shadowTlsPassword = shadowTls.shadowTlsPassword,
        trojanPassword = trojan.trojanPassword,
        shadowsocksMethod = shadowsocks.shadowsocksMethod,
        shadowsocksPassword = shadowsocks.shadowsocksPassword,
        naiveUsername = pluggableTransport.naiveUsername,
        naivePassword = pluggableTransport.naivePassword,
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
        appsScriptScriptIds = appsScript.appsScriptScriptIds,
        appsScriptGoogleIp = appsScript.appsScriptGoogleIp,
        appsScriptFrontDomain = appsScript.appsScriptFrontDomain,
        appsScriptSniHosts = appsScript.appsScriptSniHosts,
        appsScriptVerifySsl = appsScript.appsScriptVerifySsl,
        appsScriptParallelRelay = appsScript.appsScriptParallelRelay,
        appsScriptDirectHosts = appsScript.appsScriptDirectHosts,
        appsScriptAuthKey = appsScript.appsScriptAuthKey,
        finalmask = finalmask,
    )
