package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

/**
 * The single mapper from the [AppSettings] proto to the typed [SettingsSections]
 * projections.
 *
 * Every field is copied verbatim — same value, no transformation, no defaulting
 * beyond the proto3 zero values the proto already supplies. The mapper is the
 * one place that needs to know how proto fields group into feature sections;
 * every consumer downstream takes a section, never the whole proto.
 *
 * See `docs/architecture/CONFIG_CONTRACTS.md` and [SettingsSections].
 */
fun AppSettings.toSettingsSections(): SettingsSections =
    SettingsSections(
        proxy = proxySection(),
        wsTunnel = wsTunnelSection(),
        dns = dnsSection(),
        diagnostics = diagnosticsSection(),
        relay = relaySection(),
        warp = warpSection(),
        strategy = strategySection(),
        root = rootSection(),
        detection = detectionSection(),
        telemetry = telemetrySection(),
    )

private fun AppSettings.proxySection(): ProxySettingsSection =
    ProxySettingsSection(
        ripdpiMode = ripdpiMode,
        ipv6Enable = ipv6Enable,
        enableCmdSettings = enableCmdSettings,
        cmdArgs = cmdArgs,
        proxyIp = proxyIp,
        proxyPort = proxyPort,
        maxConnections = maxConnections,
        bufferSize = bufferSize,
        noDomain = noDomain,
        tcpFastOpen = tcpFastOpen,
        defaultTtl = defaultTtl,
        customTtl = customTtl,
        freezeDetectionEnabled = freezeDetectionEnabled,
        mixedInboundEnabled = mixedInboundEnabled,
        proxyAllowLan = proxyAllowLan,
        lanAuthToken = proxyLanAuthToken,
        appendHttpProxy = appendHttpProxy,
    )

private fun AppSettings.wsTunnelSection(): WsTunnelSettingsSection =
    WsTunnelSettingsSection(
        workerUrl = wsTunnelWorkerUrl,
        workerCredentialRef = wsTunnelWorkerCredentialRef,
    )

private fun AppSettings.dnsSection(): DnsSettingsSection =
    DnsSettingsSection(
        dnsIp = dnsIp,
        dnsMode = dnsMode,
        dnsProviderId = dnsProviderId,
        encryptedDnsProtocol = encryptedDnsProtocol,
        encryptedDnsHost = encryptedDnsHost,
        encryptedDnsPort = encryptedDnsPort,
        encryptedDnsTlsServerName = encryptedDnsTlsServerName,
        encryptedDnsBootstrapIps = encryptedDnsBootstrapIpsList.toList(),
        encryptedDnsDohUrl = encryptedDnsDohUrl,
        encryptedDnsDnscryptProviderName = encryptedDnsDnscryptProviderName,
        encryptedDnsDnscryptPublicKey = encryptedDnsDnscryptPublicKey,
        encryptedDnsTlsRootsPem = encryptedDnsTlsRootsPem,
        encryptedDnsOdohProxyUrl = encryptedDnsOdohProxyUrl,
        encryptedDnsOdohProxyOperatorId = encryptedDnsOdohProxyOperatorId,
        encryptedDnsOdohTargetHost = encryptedDnsOdohTargetHost,
        encryptedDnsOdohTargetPath = encryptedDnsOdohTargetPath,
        encryptedDnsOdohTargetOperatorId = encryptedDnsOdohTargetOperatorId,
        encryptedDnsOdohConfigSource = encryptedDnsOdohConfigSource,
        encryptedDnsOdohConfigsHex = encryptedDnsOdohConfigsHex,
        encryptedDnsOdohConfigsRetrievedAtSecs = encryptedDnsOdohConfigsRetrievedAtSecs,
        encryptedDnsOdohConfigsTtlSecs = encryptedDnsOdohConfigsTtlSecs,
    )

private fun AppSettings.diagnosticsSection(): DiagnosticsSettingsSection =
    DiagnosticsSettingsSection(
        diagnosticsMonitorEnabled = diagnosticsMonitorEnabled,
        diagnosticsSampleIntervalSeconds = diagnosticsSampleIntervalSeconds,
        diagnosticsDefaultScanPathMode = diagnosticsDefaultScanPathMode,
        diagnosticsAutoResumeAfterRawScan = diagnosticsAutoResumeAfterRawScan,
        diagnosticsActiveProfileId = diagnosticsActiveProfileId,
        diagnosticsHistoryRetentionDays = diagnosticsHistoryRetentionDays,
        diagnosticsExportIncludeHistory = diagnosticsExportIncludeHistory,
    )

private fun AppSettings.relaySection(): RelaySettingsSection =
    RelaySettingsSection(
        relayEnabled = relayEnabled,
        relayKind = relayKind,
        relayProfileId = relayProfileId,
        relayServer = relayServer,
        relayServerPort = relayServerPort,
        relayServerName = relayServerName,
        relayRealityPublicKey = relayRealityPublicKey,
        relayRealityShortId = relayRealityShortId,
        relayChainEntryServer = relayChainEntryServer,
        relayChainEntryPort = relayChainEntryPort,
        relayChainEntryServerName = relayChainEntryServerName,
        relayChainEntryPublicKey = relayChainEntryPublicKey,
        relayChainEntryShortId = relayChainEntryShortId,
        relayChainExitServer = relayChainExitServer,
        relayChainExitPort = relayChainExitPort,
        relayChainExitServerName = relayChainExitServerName,
        relayChainExitPublicKey = relayChainExitPublicKey,
        relayChainExitShortId = relayChainExitShortId,
        relayMasqueUrl = relayMasqueUrl,
        relayMasqueUseHttp2Fallback = relayMasqueUseHttp2Fallback,
        relayLocalSocksHost = relayLocalSocksHost,
        relayLocalSocksPort = relayLocalSocksPort,
        relayUdpEnabled = relayUdpEnabled,
        relayTcpFallbackEnabled = relayTcpFallbackEnabled,
        relayChainEntryProfileId = relayChainEntryProfileId,
        relayChainExitProfileId = relayChainExitProfileId,
        relayChainMiddleProfileIds = relayChainMiddleProfileIdsList.toList(),
        relayTuicZeroRtt = relayTuicZeroRtt,
        relayTuicCongestionControl = relayTuicCongestionControl,
        relayShadowtlsInnerProfileId = relayShadowtlsInnerProfileId,
        relayNaivePath = relayNaivePath,
        relayMasqueCloudflareGeohashEnabled = relayMasqueCloudflareGeohashEnabled,
        relayCloudflareTunnelMode = relayCloudflareTunnelMode,
        relayCloudflarePublishLocalOriginUrl = relayCloudflarePublishLocalOriginUrl,
        relayCloudflareCredentialsRef = relayCloudflareCredentialsRef,
        relayFinalmaskType = relayFinalmaskType,
        relayFinalmaskHeaderHex = relayFinalmaskHeaderHex,
        relayFinalmaskTrailerHex = relayFinalmaskTrailerHex,
        relayFinalmaskRandRange = relayFinalmaskRandRange,
        relayFinalmaskSudokuSeed = relayFinalmaskSudokuSeed,
        relayFinalmaskFragmentPackets = relayFinalmaskFragmentPackets,
        relayFinalmaskFragmentMinBytes = relayFinalmaskFragmentMinBytes,
        relayFinalmaskFragmentMaxBytes = relayFinalmaskFragmentMaxBytes,
        relayAppsScriptScriptIds = relayAppsScriptScriptIdsList.toList(),
        relayAppsScriptGoogleIp = relayAppsScriptGoogleIp,
        relayAppsScriptFrontDomain = relayAppsScriptFrontDomain,
        relayAppsScriptVerifySsl = relayAppsScriptVerifySsl,
        relayAppsScriptParallelRelay = relayAppsScriptParallelRelay,
        relayAppsScriptSniHosts = relayAppsScriptSniHostsList.toList(),
        relayAppsScriptDirectHosts = relayAppsScriptDirectHostsList.toList(),
        relayVlessTransport = relayVlessTransport,
        relayXhttpPath = relayXhttpPath,
        relayXhttpHost = relayXhttpHost,
        relayXhttpMode = relayXhttpMode,
        relayOutboundBindIp = relayOutboundBindIp,
        relayDnsOverTunnelEnabled = effectiveRelayDnsOverTunnelEnabled(),
    )

private fun AppSettings.warpSection(): WarpSettingsSection =
    WarpSettingsSection(
        warpEnabled = warpEnabled,
        warpRouteMode = warpRouteMode,
        warpRouteHosts = warpRouteHosts,
        warpBuiltinRulesEnabled = warpBuiltinRulesEnabled,
        warpEndpointSelectionMode = warpEndpointSelectionMode,
        warpManualEndpointHost = warpManualEndpointHost,
        warpManualEndpointV4 = warpManualEndpointV4,
        warpManualEndpointV6 = warpManualEndpointV6,
        warpManualEndpointPort = warpManualEndpointPort,
        warpScannerEnabled = warpScannerEnabled,
        warpScannerParallelism = warpScannerParallelism,
        warpScannerMaxRttMs = warpScannerMaxRttMs,
        warpAmneziaEnabled = warpAmneziaEnabled,
        warpAmneziaJc = warpAmneziaJc,
        warpAmneziaJmin = warpAmneziaJmin,
        warpAmneziaJmax = warpAmneziaJmax,
        warpAmneziaH1 = warpAmneziaH1,
        warpAmneziaH2 = warpAmneziaH2,
        warpAmneziaH3 = warpAmneziaH3,
        warpAmneziaH4 = warpAmneziaH4,
        warpAmneziaS1 = warpAmneziaS1,
        warpAmneziaS2 = warpAmneziaS2,
        warpAmneziaS3 = warpAmneziaS3,
        warpAmneziaS4 = warpAmneziaS4,
        warpAmneziaPreset = warpAmneziaPreset,
        warpProfileId = warpProfileId,
        warpAccountKind = warpAccountKind,
        warpZeroTrustOrg = warpZeroTrustOrg,
        warpSetupState = warpSetupState,
        warpLastScannerMode = warpLastScannerMode,
    )

private fun AppSettings.strategySection(): StrategySettingsSection =
    StrategySettingsSection(
        strategyEvolution = strategyEvolution,
        evolutionEpsilon = evolutionEpsilon,
        evolutionExperimentTtlMs = evolutionExperimentTtlMs,
        evolutionDecayHalfLifeMs = evolutionDecayHalfLifeMs,
        evolutionCooldownAfterFailures = evolutionCooldownAfterFailures,
        evolutionCooldownMs = evolutionCooldownMs,
        strategyChainYaml = strategyChainYaml,
        strategyPackChannel = strategyPackChannel,
        strategyPackPinnedId = strategyPackPinnedId,
        strategyPackPinnedVersion = strategyPackPinnedVersion,
        strategyPackRefreshPolicy = strategyPackRefreshPolicy,
        strategyPackAllowRollbackOverride = strategyPackAllowRollbackOverride,
    )

private fun AppSettings.rootSection(): RootSettingsSection =
    RootSettingsSection(
        rootModeEnabled = rootModeEnabled,
    )

private fun AppSettings.detectionSection(): DetectionSettingsSection =
    DetectionSettingsSection(
        detectionCheckIncludeBypass = detectionCheckIncludeBypass,
        detectionCheckIncludeLocation = detectionCheckIncludeLocation,
        detectionCheckRttTriangulationEnabled = detectionCheckRttTriangulationEnabled,
        detectionCheckPrivacyModeEnabled = detectionCheckPrivacyModeEnabled,
        rknDiagnosticsFetchSelfInfoEnabled = rknDiagnosticsFetchSelfInfoEnabled,
        detectionCheckCdnPullingEnabled = detectionCheckCdnPullingEnabled,
        compressionProbeIncludeZstd = compressionProbeIncludeZstd,
        detectionCheckDebugModeEnabled = detectionCheckDebugModeEnabled,
        detectionCheckColorVisionMode = detectionCheckColorVisionMode,
        detectionCheckProtanopiaVariantUnlocked = detectionCheckProtanopiaVariantUnlocked,
        detectionCheckNetworkRequestsEnabled = detectionCheckNetworkRequestsEnabled,
        detectionCheckCdnPullingMeduzaEnabled = detectionCheckCdnPullingMeduzaEnabled,
        detectionCheckCallTransportProbeEnabled = detectionCheckCallTransportProbeEnabled,
        detectionCheckXrayApiScanEnabled = detectionCheckXrayApiScanEnabled,
        detectionCheckTunProbeMode = detectionCheckTunProbeMode,
        detectionCheckPortRangeMode = detectionCheckPortRangeMode,
        detectionCheckCustomPortStart = detectionCheckCustomPortStart,
        detectionCheckCustomPortEnd = detectionCheckCustomPortEnd,
        detectionCheckDnsResolverMode = detectionCheckDnsResolverMode,
        detectionCheckDnsPreset = detectionCheckDnsPreset,
        detectionCheckDnsDirectServers = detectionCheckDnsDirectServers,
        detectionCheckDnsDohUrl = detectionCheckDnsDohUrl,
        detectionCheckDnsDohBootstrapIps = detectionCheckDnsDohBootstrapIps,
        detectionCheckDnsRouteThroughProxy = detectionCheckDnsRouteThroughProxy,
        dpiSuiteConcurrency = dpiSuiteConcurrency,
        detectionDiagnosticRandomHostnamesEnabled = detectionDiagnosticRandomHostnamesEnabled,
        detectionDiagnosticTlsKeylogPath = detectionDiagnosticTlsKeylogPath,
    )

private fun AppSettings.telemetrySection(): TelemetrySettingsSection =
    TelemetrySettingsSection(
        communityComparisonEnabled = communityComparisonEnabled,
        communityApiUrl = communityApiUrl,
    )
