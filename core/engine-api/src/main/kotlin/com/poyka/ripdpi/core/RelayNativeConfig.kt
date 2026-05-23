package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.RelayCongestionControlBbr
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

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
    val vlessTransport: String = RelayVlessTransportRealityTcp,
    val vlessUuid: String? = null,
)

@Serializable
data class ResolvedRipDpiRelayConfig(
    val enabled: Boolean,
    val kind: String,
    val profileId: String,
    val outboundBindIp: String = "",
    val server: String,
    val serverPort: Int,
    val serverName: String,
    val realityPublicKey: String,
    val realityShortId: String,
    val vlessTransport: String = RelayVlessTransportRealityTcp,
    val xhttpPath: String = "",
    val xhttpHost: String = "",
    val cloudflareTunnelMode: String = com.poyka.ripdpi.data.RelayCloudflareTunnelModeConsumeExisting,
    val cloudflarePublishLocalOriginUrl: String = "",
    val cloudflareCredentialsRef: String = "",
    val chainEntryServer: String,
    val chainEntryPort: Int,
    val chainEntryServerName: String,
    val chainEntryPublicKey: String,
    val chainEntryShortId: String,
    val chainEntryProfileId: String = "",
    val chainExitServer: String,
    val chainExitPort: Int,
    val chainExitServerName: String,
    val chainExitPublicKey: String,
    val chainExitShortId: String,
    val chainExitProfileId: String = "",
    val masqueUrl: String,
    val masqueUseHttp2Fallback: Boolean,
    val masqueCloudflareGeohashEnabled: Boolean = false,
    val tuicZeroRtt: Boolean = false,
    val tuicCongestionControl: String = RelayCongestionControlBbr,
    val shadowTlsInnerProfileId: String = "",
    val shadowTlsInner: ResolvedShadowTlsInnerRelayConfig? = null,
    val naivePath: String = "",
    val ptBridgeLine: String = "",
    val ptWebTunnelUrl: String = "",
    val ptSnowflakeBrokerUrl: String = "",
    val ptSnowflakeFrontDomain: String = "",
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
    val tuicUuid: String? = null,
    val tuicPassword: String? = null,
    val shadowTlsPassword: String? = null,
    val naiveUsername: String? = null,
    val naivePassword: String? = null,
    val tlsFingerprintProfile: String = TlsFingerprintProfileChromeStable,
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
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val schemaVersion: Int = RelayNativeConfigSchemaVersion,
)

/**
 * Current relay native-config wire schema version — carried as the additive
 * `schemaVersion` field on [ResolvedRipDpiRelayConfig]. A payload with no
 * `schemaVersion` is a legacy payload; the Rust side defaults it to this same
 * value. Bumped only on a genuinely breaking shape change. See
 * `docs/architecture/CONFIG_CONTRACTS.md` §8.
 */
const val RelayNativeConfigSchemaVersion: Int = 1

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

/** Chain-relay entry and exit hop fields. */
data class RelayChainSection(
    val chainEntryServer: String,
    val chainEntryPort: Int,
    val chainEntryServerName: String,
    val chainEntryPublicKey: String,
    val chainEntryShortId: String,
    val chainEntryProfileId: String,
    val chainEntryUuid: String?,
    val chainExitServer: String,
    val chainExitPort: Int,
    val chainExitServerName: String,
    val chainExitPublicKey: String,
    val chainExitShortId: String,
    val chainExitProfileId: String,
    val chainExitUuid: String?,
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

/** Hysteria2 credentials. */
data class RelayHysteria2Section(
    val hysteriaPassword: String?,
    val hysteriaSalamanderKey: String?,
)

/** Pluggable-transport fields: NaiveProxy plus obfs4 / WebTunnel / Snowflake. */
data class RelayPluggableTransportSection(
    val naivePath: String,
    val naiveUsername: String?,
    val naivePassword: String?,
    val ptBridgeLine: String,
    val ptWebTunnelUrl: String,
    val ptSnowflakeBrokerUrl: String,
    val ptSnowflakeFrontDomain: String,
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
    val hysteria2: RelayHysteria2Section,
    val pluggableTransport: RelayPluggableTransportSection,
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
    )

private fun ResolvedRipDpiRelayConfig.vlessSection(): RelayVlessSection =
    RelayVlessSection(
        realityPublicKey = realityPublicKey,
        realityShortId = realityShortId,
        vlessTransport = vlessTransport,
        xhttpPath = xhttpPath,
        xhttpHost = xhttpHost,
        vlessUuid = vlessUuid,
    )

private fun ResolvedRipDpiRelayConfig.chainSection(): RelayChainSection =
    RelayChainSection(
        chainEntryServer = chainEntryServer,
        chainEntryPort = chainEntryPort,
        chainEntryServerName = chainEntryServerName,
        chainEntryPublicKey = chainEntryPublicKey,
        chainEntryShortId = chainEntryShortId,
        chainEntryProfileId = chainEntryProfileId,
        chainEntryUuid = chainEntryUuid,
        chainExitServer = chainExitServer,
        chainExitPort = chainExitPort,
        chainExitServerName = chainExitServerName,
        chainExitPublicKey = chainExitPublicKey,
        chainExitShortId = chainExitShortId,
        chainExitProfileId = chainExitProfileId,
        chainExitUuid = chainExitUuid,
    )

private fun ResolvedRipDpiRelayConfig.masqueSection(): RelayMasqueSection =
    RelayMasqueSection(
        masqueUrl = masqueUrl,
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

private fun ResolvedRipDpiRelayConfig.hysteria2Section(): RelayHysteria2Section =
    RelayHysteria2Section(
        hysteriaPassword = hysteriaPassword,
        hysteriaSalamanderKey = hysteriaSalamanderKey,
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
fun ResolvedRipDpiRelayConfig.toSections(): RelayConfigSections =
    RelayConfigSections(
        common = commonSection(),
        vless = vlessSection(),
        chain = chainSection(),
        masque = masqueSection(),
        tuic = tuicSection(),
        shadowTls = shadowTlsSection(),
        hysteria2 = hysteria2Section(),
        pluggableTransport = pluggableTransportSection(),
        cloudflare = cloudflareSection(),
        appsScript = appsScriptSection(),
        finalmask = finalmask,
    )

/**
 * Flatten the section models back into the [ResolvedRipDpiRelayConfig] wire
 * DTO. Inverse of [toSections]: `config.toSections().toResolvedConfig()`
 * reproduces `config` exactly.
 */
fun RelayConfigSections.toResolvedConfig(): ResolvedRipDpiRelayConfig =
    ResolvedRipDpiRelayConfig(
        enabled = common.enabled,
        kind = common.kind,
        profileId = common.profileId,
        outboundBindIp = common.outboundBindIp,
        server = common.server,
        serverPort = common.serverPort,
        serverName = common.serverName,
        realityPublicKey = vless.realityPublicKey,
        realityShortId = vless.realityShortId,
        vlessTransport = vless.vlessTransport,
        xhttpPath = vless.xhttpPath,
        xhttpHost = vless.xhttpHost,
        cloudflareTunnelMode = cloudflare.cloudflareTunnelMode,
        cloudflarePublishLocalOriginUrl = cloudflare.cloudflarePublishLocalOriginUrl,
        cloudflareCredentialsRef = cloudflare.cloudflareCredentialsRef,
        chainEntryServer = chain.chainEntryServer,
        chainEntryPort = chain.chainEntryPort,
        chainEntryServerName = chain.chainEntryServerName,
        chainEntryPublicKey = chain.chainEntryPublicKey,
        chainEntryShortId = chain.chainEntryShortId,
        chainEntryProfileId = chain.chainEntryProfileId,
        chainExitServer = chain.chainExitServer,
        chainExitPort = chain.chainExitPort,
        chainExitServerName = chain.chainExitServerName,
        chainExitPublicKey = chain.chainExitPublicKey,
        chainExitShortId = chain.chainExitShortId,
        chainExitProfileId = chain.chainExitProfileId,
        masqueUrl = masque.masqueUrl,
        masqueUseHttp2Fallback = masque.masqueUseHttp2Fallback,
        masqueCloudflareGeohashEnabled = masque.masqueCloudflareGeohashEnabled,
        tuicZeroRtt = tuic.tuicZeroRtt,
        tuicCongestionControl = tuic.tuicCongestionControl,
        shadowTlsInnerProfileId = shadowTls.shadowTlsInnerProfileId,
        shadowTlsInner = shadowTls.shadowTlsInner,
        naivePath = pluggableTransport.naivePath,
        ptBridgeLine = pluggableTransport.ptBridgeLine,
        ptWebTunnelUrl = pluggableTransport.ptWebTunnelUrl,
        ptSnowflakeBrokerUrl = pluggableTransport.ptSnowflakeBrokerUrl,
        ptSnowflakeFrontDomain = pluggableTransport.ptSnowflakeFrontDomain,
        localSocksHost = common.localSocksHost,
        localSocksPort = common.localSocksPort,
        udpEnabled = common.udpEnabled,
        tcpFallbackEnabled = common.tcpFallbackEnabled,
        quicBindLowPort = common.quicBindLowPort,
        quicMigrateAfterHandshake = common.quicMigrateAfterHandshake,
        vlessUuid = vless.vlessUuid,
        chainEntryUuid = chain.chainEntryUuid,
        chainExitUuid = chain.chainExitUuid,
        hysteriaPassword = hysteria2.hysteriaPassword,
        hysteriaSalamanderKey = hysteria2.hysteriaSalamanderKey,
        tuicUuid = tuic.tuicUuid,
        tuicPassword = tuic.tuicPassword,
        shadowTlsPassword = shadowTls.shadowTlsPassword,
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
