package com.poyka.ripdpi.data

/*
 * Typed, immutable, feature-scoped projections over the `com.poyka.ripdpi.proto.AppSettings`
 * protobuf.
 *
 * `AppSettings` is one large feature container — ~280 fields across every
 * subsystem. A mapper or coordinator that needs only the relay fields should
 * not have to understand the whole proto. These sections are *read-only
 * projections*: each carries exactly the fields of one feature area, copied
 * verbatim from the proto by [AppSettings.toSettingsSections].
 *
 * They change no wire behavior — the `AppSettings` proto, its field numbers,
 * and the persisted DataStore format are untouched. A section is purely a
 * narrower lens on settings already decided. Field defaults mirror the proto3
 * zero values so a section is meaningful even before the mapper fills it.
 *
 * The section models are deliberately pure data: no Android `Context`, no
 * service or JNI type, no dependency-injection annotation. `SettingsSectionPurityTest`
 * enforces that. See `docs/architecture/CONFIG_CONTRACTS.md`.
 */

/** The local proxy listener config inputs and command-line transport settings. */
data class ProxySettingsSection(
    val ripdpiMode: String = "",
    val ipv6Enable: Boolean = false,
    val enableCmdSettings: Boolean = false,
    val cmdArgs: String = "",
    val proxyIp: String = "",
    val proxyPort: Int = 0,
    val maxConnections: Int = 0,
    val bufferSize: Int = 0,
    val noDomain: Boolean = false,
    val tcpFastOpen: Boolean = false,
    val defaultTtl: Int = 0,
    val customTtl: Boolean = false,
    val freezeDetectionEnabled: Boolean = false,
    val mixedInboundEnabled: Boolean = false,
    val proxyAllowLan: Boolean = false,
    val lanAuthToken: String = "",
    val appendHttpProxy: Boolean = false,
)

/** Optional Telegram WebSocket tunnel route settings. Bearer material is not persisted here. */
data class WsTunnelSettingsSection(
    val workerUrl: String = "",
    val workerCredentialRef: String = "",
)

/** Plain and encrypted DNS resolution settings. */
data class DnsSettingsSection(
    val dnsIp: String = "",
    val dnsMode: String = "",
    val dnsProviderId: String = "",
    val encryptedDnsProtocol: String = "",
    val encryptedDnsHost: String = "",
    val encryptedDnsPort: Int = 0,
    val encryptedDnsTlsServerName: String = "",
    val encryptedDnsBootstrapIps: List<String> = emptyList(),
    val encryptedDnsDohUrl: String = "",
    val encryptedDnsDnscryptProviderName: String = "",
    val encryptedDnsDnscryptPublicKey: String = "",
    val encryptedDnsTlsRootsPem: String = "",
    val encryptedDnsOdohProxyUrl: String = "",
    val encryptedDnsOdohProxyOperatorId: String = "",
    val encryptedDnsOdohTargetHost: String = "",
    val encryptedDnsOdohTargetPath: String = "",
    val encryptedDnsOdohTargetOperatorId: String = "",
    val encryptedDnsOdohConfigSource: String = "",
    val encryptedDnsOdohConfigsHex: String = "",
    val encryptedDnsOdohConfigsRetrievedAtSecs: Long = 0L,
    val encryptedDnsOdohConfigsTtlSecs: Long = 0L,
)

/** Diagnostics monitor and scan-session settings. */
data class DiagnosticsSettingsSection(
    val diagnosticsMonitorEnabled: Boolean = false,
    val diagnosticsSampleIntervalSeconds: Int = 0,
    val diagnosticsDefaultScanPathMode: String = "",
    val diagnosticsAutoResumeAfterRawScan: Boolean = false,
    val diagnosticsActiveProfileId: String = "",
    val diagnosticsHistoryRetentionDays: Int = 0,
    val diagnosticsExportIncludeHistory: Boolean = false,
)

/** Tier-3 upstream relay settings — every `relay_*` proto field. */
data class RelaySettingsSection(
    val relayEnabled: Boolean = false,
    val relayKind: String = "",
    val relayProfileId: String = "",
    val relayServer: String = "",
    val relayServerPort: Int = 0,
    val relayServerName: String = "",
    val relayRealityPublicKey: String = "",
    val relayRealityShortId: String = "",
    val relayChainEntryServer: String = "",
    val relayChainEntryPort: Int = 0,
    val relayChainEntryServerName: String = "",
    val relayChainEntryPublicKey: String = "",
    val relayChainEntryShortId: String = "",
    val relayChainExitServer: String = "",
    val relayChainExitPort: Int = 0,
    val relayChainExitServerName: String = "",
    val relayChainExitPublicKey: String = "",
    val relayChainExitShortId: String = "",
    val relayMasqueUrl: String = "",
    val relayMasqueUseHttp2Fallback: Boolean = false,
    val relayLocalSocksHost: String = "",
    val relayLocalSocksPort: Int = 0,
    val relayUdpEnabled: Boolean = false,
    val relayTcpFallbackEnabled: Boolean = false,
    val relayChainEntryProfileId: String = "",
    val relayChainExitProfileId: String = "",
    val relayChainMiddleProfileIds: List<String> = emptyList(),
    val relayTuicZeroRtt: Boolean = false,
    val relayTuicCongestionControl: String = "",
    val relayShadowtlsInnerProfileId: String = "",
    val relayNaivePath: String = "",
    val relayMasqueCloudflareGeohashEnabled: Boolean = false,
    val relayCloudflareTunnelMode: String = "",
    val relayCloudflarePublishLocalOriginUrl: String = "",
    val relayCloudflareCredentialsRef: String = "",
    val relayFinalmaskType: String = "",
    val relayFinalmaskHeaderHex: String = "",
    val relayFinalmaskTrailerHex: String = "",
    val relayFinalmaskRandRange: String = "",
    val relayFinalmaskSudokuSeed: String = "",
    val relayFinalmaskFragmentPackets: Int = 0,
    val relayFinalmaskFragmentMinBytes: Int = 0,
    val relayFinalmaskFragmentMaxBytes: Int = 0,
    val relayAppsScriptScriptIds: List<String> = emptyList(),
    val relayAppsScriptGoogleIp: String = "",
    val relayAppsScriptFrontDomain: String = "",
    val relayAppsScriptVerifySsl: Boolean = false,
    val relayAppsScriptParallelRelay: Boolean = false,
    val relayAppsScriptSniHosts: List<String> = emptyList(),
    val relayAppsScriptDirectHosts: List<String> = emptyList(),
    val relayVlessTransport: String = "",
    val relayXhttpPath: String = "",
    val relayXhttpHost: String = "",
    val relayXhttpMode: String = "",
    val relayOutboundBindIp: String = "",
    val relayDnsOverTunnelEnabled: Boolean = true,
)

/** WARP integration, scanner, Amnezia obfuscation, and active-profile metadata. */
data class WarpSettingsSection(
    val warpEnabled: Boolean = false,
    val warpRouteMode: String = "",
    val warpRouteHosts: String = "",
    val warpBuiltinRulesEnabled: Boolean = false,
    val warpEndpointSelectionMode: String = "",
    val warpManualEndpointHost: String = "",
    val warpManualEndpointV4: String = "",
    val warpManualEndpointV6: String = "",
    val warpManualEndpointPort: Int = 0,
    val warpScannerEnabled: Boolean = false,
    val warpScannerParallelism: Int = 0,
    val warpScannerMaxRttMs: Int = 0,
    val warpAmneziaEnabled: Boolean = false,
    val warpAmneziaJc: Int = 0,
    val warpAmneziaJmin: Int = 0,
    val warpAmneziaJmax: Int = 0,
    val warpAmneziaH1: Long = 0L,
    val warpAmneziaH2: Long = 0L,
    val warpAmneziaH3: Long = 0L,
    val warpAmneziaH4: Long = 0L,
    val warpAmneziaS1: Int = 0,
    val warpAmneziaS2: Int = 0,
    val warpAmneziaS3: Int = 0,
    val warpAmneziaS4: Int = 0,
    val warpAmneziaPreset: String = "",
    val warpProfileId: String = "",
    val warpAccountKind: String = "",
    val warpZeroTrustOrg: String = "",
    val warpSetupState: String = "",
    val warpLastScannerMode: String = "",
)

/** Strategy evolution and strategy-pack channel settings. */
data class StrategySettingsSection(
    val strategyEvolution: Boolean = false,
    val evolutionEpsilon: Double = 0.0,
    val evolutionExperimentTtlMs: Long = 0L,
    val evolutionDecayHalfLifeMs: Long = 0L,
    val evolutionCooldownAfterFailures: Int = 0,
    val evolutionCooldownMs: Long = 0L,
    val strategyChainYaml: String = "",
    val strategyPackChannel: String = "",
    val strategyPackPinnedId: String = "",
    val strategyPackPinnedVersion: String = "",
    val strategyPackRefreshPolicy: String = "",
    val strategyPackAllowRollbackOverride: Boolean = false,
)

/** Root-mode enablement — the single seam for the opt-in root-helper feature. */
data class RootSettingsSection(
    val rootModeEnabled: Boolean = false,
)

/** Detection-check probe settings — every `detection_check_*` and diagnostic-probe field. */
data class DetectionSettingsSection(
    val detectionCheckIncludeBypass: Boolean = false,
    val detectionCheckIncludeLocation: Boolean = false,
    val detectionCheckRttTriangulationEnabled: Boolean = false,
    val detectionCheckPrivacyModeEnabled: Boolean = false,
    val rknDiagnosticsFetchSelfInfoEnabled: Boolean = false,
    val detectionCheckCdnPullingEnabled: Boolean = false,
    val compressionProbeIncludeZstd: Boolean = false,
    val detectionCheckDebugModeEnabled: Boolean = false,
    val detectionCheckColorVisionMode: String = "",
    val detectionCheckProtanopiaVariantUnlocked: Boolean = false,
    val detectionCheckNetworkRequestsEnabled: Boolean = false,
    val detectionCheckCdnPullingMeduzaEnabled: Boolean = false,
    val detectionCheckCallTransportProbeEnabled: Boolean = false,
    val detectionCheckXrayApiScanEnabled: Boolean = false,
    val detectionCheckTunProbeMode: String = "",
    val detectionCheckPortRangeMode: String = "",
    val detectionCheckCustomPortStart: Int = 0,
    val detectionCheckCustomPortEnd: Int = 0,
    val detectionCheckDnsResolverMode: String = "",
    val detectionCheckDnsPreset: String = "",
    val detectionCheckDnsDirectServers: String = "",
    val detectionCheckDnsDohUrl: String = "",
    val detectionCheckDnsDohBootstrapIps: String = "",
    val detectionCheckDnsRouteThroughProxy: Boolean = false,
    val dpiSuiteConcurrency: Int = 0,
    val detectionDiagnosticRandomHostnamesEnabled: Boolean = false,
    val detectionDiagnosticTlsKeylogPath: String = "",
)

/** Anonymous, opt-in community-comparison telemetry settings. */
data class TelemetrySettingsSection(
    val communityComparisonEnabled: Boolean = false,
    val communityApiUrl: String = "",
)

/**
 * The complete set of typed section projections for one `AppSettings` value.
 *
 * Produced by [AppSettings.toSettingsSections]. A consumer takes the one
 * section it needs (`sections.diagnostics`, `sections.relay`, …) instead of
 * the whole proto.
 */
data class SettingsSections(
    val proxy: ProxySettingsSection,
    val wsTunnel: WsTunnelSettingsSection,
    val dns: DnsSettingsSection,
    val diagnostics: DiagnosticsSettingsSection,
    val relay: RelaySettingsSection,
    val warp: WarpSettingsSection,
    val strategy: StrategySettingsSection,
    val root: RootSettingsSection,
    val detection: DetectionSettingsSection,
    val telemetry: TelemetrySettingsSection,
)
