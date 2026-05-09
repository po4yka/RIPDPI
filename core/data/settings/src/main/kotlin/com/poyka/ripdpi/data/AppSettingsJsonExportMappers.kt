package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

private const val DefaultHttpsPort = 443

internal fun AppSettings.toSnapshot(): AppSettingsSnapshot =
    AppSettingsSnapshot(
        appTheme = appTheme,
        mode = Mode.fromString(ripdpiMode.ifEmpty { AppSettingsSerializer.defaultValue.ripdpiMode }),
    ).withDnsSnapshot(this)
        .withProxyDesyncSnapshot(this)
        .withQuicAdaptiveSnapshot(this)
        .withWarpSnapshot(this)
        .withRelaySnapshot(this)
        .withRoutingSnapshot(this)
        .withUiRuntimeSnapshot(this)

private fun AppSettingsSnapshot.withDnsSnapshot(settings: AppSettings): AppSettingsSnapshot {
    val activeDns = settings.activeDnsSettings()
    return copy(
        dnsIp = activeDns.dnsIp,
        dnsMode = activeDns.mode,
        dnsProviderId = activeDns.providerId,
        encryptedDnsProtocol = activeDns.encryptedDnsProtocol,
        encryptedDnsHost = activeDns.encryptedDnsHost,
        encryptedDnsPort = activeDns.encryptedDnsPort,
        encryptedDnsTlsServerName = activeDns.encryptedDnsTlsServerName,
        encryptedDnsBootstrapIps = activeDns.encryptedDnsBootstrapIps,
        encryptedDnsDohUrl = activeDns.encryptedDnsDohUrl,
        encryptedDnsDnscryptProviderName = activeDns.encryptedDnsDnscryptProviderName,
        encryptedDnsDnscryptPublicKey = activeDns.encryptedDnsDnscryptPublicKey,
        ipv6Enabled = settings.ipv6Enable,
    )
}

@Suppress("LongMethod")
private fun AppSettingsSnapshot.withProxyDesyncSnapshot(settings: AppSettings): AppSettingsSnapshot =
    copy(
        enableCommandLineSettings = settings.enableCmdSettings,
        commandLineArgs = settings.cmdArgs,
        proxyIp = settings.proxyIp,
        proxyPort = settings.proxyPort,
        maxConnections = settings.maxConnections,
        bufferSize = settings.bufferSize,
        noDomain = settings.noDomain,
        tcpFastOpen = settings.tcpFastOpen,
        defaultTtl = settings.defaultTtl,
        customTtl = settings.customTtl,
        fakeTtl = settings.fakeTtl,
        adaptiveFakeTtlEnabled = settings.adaptiveFakeTtlEnabled,
        adaptiveFakeTtlDelta = settings.effectiveAdaptiveFakeTtlDelta(),
        adaptiveFakeTtlMin = settings.effectiveAdaptiveFakeTtlMin(),
        adaptiveFakeTtlMax = settings.effectiveAdaptiveFakeTtlMax(),
        adaptiveFakeTtlFallback = settings.effectiveAdaptiveFakeTtlFallback(),
        fakeSni = settings.fakeSni,
        fakeOffset = settings.fakeOffset,
        fakeOffsetMarker = settings.fakeOffsetMarker,
        fakeTlsSource = normalizeFakeTlsSource(settings.fakeTlsSource),
        fakeTlsSecondaryProfile = settings.fakeTlsSecondaryProfile,
        fakeTcpTimestampEnabled = settings.fakeTcpTimestampEnabled,
        fakeTcpTimestampDeltaTicks = settings.fakeTcpTimestampDeltaTicks,
        fakeTlsUseOriginal = settings.fakeTlsUseOriginal,
        fakeTlsRandomize = settings.fakeTlsRandomize,
        fakeTlsDupSessionId = settings.fakeTlsDupSessionId,
        fakeTlsPadEncap = settings.fakeTlsPadEncap,
        fakeTlsSize = settings.fakeTlsSize,
        fakeTlsSniMode = settings.effectiveFakeTlsSniMode(),
        ipIdMode = settings.effectiveIpIdMode(),
        httpFakeProfile = settings.effectiveHttpFakeProfile(),
        tlsFakeProfile = settings.effectiveTlsFakeProfile(),
        oobData = settings.oobData,
        dropSack = settings.dropSack,
        desyncHttp = settings.desyncHttp,
        desyncHttps = settings.desyncHttps,
        desyncUdp = settings.desyncUdp,
        hostsMode = settings.hostsMode,
        hostsBlacklist = settings.hostsBlacklist,
        hostsWhitelist = settings.hostsWhitelist,
        udpFakeProfile = settings.effectiveUdpFakeProfile(),
        hostMixedCase = settings.hostMixedCase,
        domainMixedCase = settings.domainMixedCase,
        hostRemoveSpaces = settings.hostRemoveSpaces,
        httpMethodEol = settings.httpMethodEol,
        httpUnixEol = settings.httpUnixEol,
        httpMethodSpace = settings.httpMethodSpace,
        httpHostPad = settings.httpHostPad,
        tcpChainSteps = settings.toTcpChainSnapshots(),
        udpChainSteps = settings.toUdpChainSnapshots(),
        desyncAnyProtocol = settings.desyncAnyProtocol,
        groupActivationFilter =
            if (settings.hasGroupActivationFilter()) {
                settings.groupActivationFilter.toModel().let(::normalizeActivationFilter)
            } else {
                ActivationFilterModel()
            },
    )

private fun AppSettings.toTcpChainSnapshots(): List<AppSettingsTcpChainSnapshot> =
    tcpChainStepsList.map {
        AppSettingsTcpChainSnapshot(
            kind = it.kind,
            marker = it.marker,
            midhostMarker = it.midhostMarker.takeIf(String::isNotBlank),
            fakeHostTemplate = it.fakeHostTemplate.takeIf(String::isNotBlank),
            overlapSize = it.overlapSize.takeIf { value -> value > 0 },
            fakeMode =
                it.fakeMode.takeIf { value ->
                    value.isNotBlank() && value != SeqOverlapFakeModeProfile
                },
            fragmentCount = it.fragmentCount,
            minFragmentSize = it.minFragmentSize,
            maxFragmentSize = it.maxFragmentSize,
            activationFilter = if (it.hasActivationFilter()) it.activationFilter.toModel() else ActivationFilterModel(),
            ipv6ExtensionProfile = normalizeStrategyIpv6ExtensionProfile(it.ipv6ExtensionProfile),
            tcpFlagsSet = it.tcpFlagsSet.takeIf(String::isNotBlank),
            tcpFlagsUnset = it.tcpFlagsUnset.takeIf(String::isNotBlank),
            tcpFlagsOrigSet = it.tcpFlagsOrigSet.takeIf(String::isNotBlank),
            tcpFlagsOrigUnset = it.tcpFlagsOrigUnset.takeIf(String::isNotBlank),
        )
    }

private fun AppSettings.toUdpChainSnapshots(): List<AppSettingsUdpChainSnapshot> =
    udpChainStepsList.map {
        AppSettingsUdpChainSnapshot(
            kind = it.kind,
            count = it.count,
            splitBytes = it.splitBytes,
            activationFilter = if (it.hasActivationFilter()) it.activationFilter.toModel() else ActivationFilterModel(),
            ipv6ExtensionProfile = normalizeStrategyIpv6ExtensionProfile(it.ipv6ExtensionProfile),
        )
    }

@Suppress("LongMethod")
private fun AppSettingsSnapshot.withQuicAdaptiveSnapshot(settings: AppSettings): AppSettingsSnapshot =
    copy(
        quicInitialMode = settings.effectiveQuicInitialMode(),
        quicSupportV1 = settings.effectiveQuicSupportV1(),
        quicSupportV2 = settings.effectiveQuicSupportV2(),
        quicFakeProfile = settings.effectiveQuicFakeProfile(),
        quicFakeHost = settings.effectiveQuicFakeHost(),
        quicBindLowPort = settings.quicBindLowPort,
        quicMigrateAfterHandshake = settings.quicMigrateAfterHandshake,
        hostAutolearnEnabled = settings.hostAutolearnEnabled,
        hostAutolearnPenaltyTtlHours = normalizeHostAutolearnPenaltyTtlHours(settings.hostAutolearnPenaltyTtlHours),
        hostAutolearnMaxHosts = normalizeHostAutolearnMaxHosts(settings.hostAutolearnMaxHosts),
        networkStrategyMemoryEnabled = settings.networkStrategyMemoryEnabled,
        strategyEvolution = settings.strategyEvolution,
        evolutionEpsilon = settings.evolutionEpsilon.takeIf { it in 0.0..1.0 } ?: DefaultEvolutionEpsilon,
        evolutionExperimentTtlMs =
            settings.evolutionExperimentTtlMs.takeIf { it > 0 } ?: DefaultEvolutionExperimentTtlMs,
        evolutionDecayHalfLifeMs =
            settings.evolutionDecayHalfLifeMs.takeIf { it > 0 } ?: DefaultEvolutionDecayHalfLifeMs,
        evolutionCooldownAfterFailures =
            settings.evolutionCooldownAfterFailures.takeIf { it > 0 } ?: DefaultEvolutionCooldownAfterFailures,
        evolutionCooldownMs = settings.evolutionCooldownMs.takeIf { it > 0 } ?: DefaultEvolutionCooldownMs,
        entropyPaddingTargetPermil = settings.entropyPaddingTargetPermil.coerceAtLeast(0),
        entropyPaddingMax = settings.entropyPaddingMax.takeIf { it > 0 } ?: DefaultEntropyPaddingMax,
        entropyMode = entropyModeFromProto(settings.entropyMode),
        shannonEntropyTargetPermil = settings.shannonEntropyTargetPermil.coerceAtLeast(0),
        tlsFingerprintProfile = normalizeTlsFingerprintProfile(settings.tlsFingerprintProfile),
        strategyPackChannel = normalizeStrategyPackChannel(settings.strategyPackChannel),
        strategyPackPinnedId = settings.strategyPackPinnedId,
        strategyPackPinnedVersion = settings.strategyPackPinnedVersion,
        strategyPackRefreshPolicy = normalizeStrategyPackRefreshPolicy(settings.strategyPackRefreshPolicy),
        strategyPackAllowRollbackOverride = settings.strategyPackAllowRollbackOverride,
        adaptiveFallbackEnabled = settings.adaptiveFallbackEnabled,
        adaptiveFallbackTorst = settings.adaptiveFallbackTorst,
        adaptiveFallbackTlsErr = settings.adaptiveFallbackTlsErr,
        adaptiveFallbackHttpRedirect = settings.adaptiveFallbackHttpRedirect,
        adaptiveFallbackConnectFailure = settings.adaptiveFallbackConnectFailure,
        adaptiveFallbackAutoSort = settings.adaptiveFallbackAutoSort,
        adaptiveFallbackCacheTtlSeconds =
            normalizeAdaptiveFallbackCacheTtlSeconds(settings.adaptiveFallbackCacheTtlSeconds),
        adaptiveFallbackCachePrefixV4 = normalizeAdaptiveFallbackCachePrefixV4(settings.adaptiveFallbackCachePrefixV4),
        wsTunnelEnabled = settings.wsTunnelEnabled,
        wsTunnelMode = settings.effectiveWsTunnelMode(),
    )

@Suppress("LongMethod")
private fun AppSettingsSnapshot.withWarpSnapshot(settings: AppSettings): AppSettingsSnapshot =
    copy(
        warpEnabled = settings.warpEnabled,
        warpRouteMode = normalizeWarpRouteMode(settings.warpRouteMode),
        warpRouteHosts = settings.warpRouteHosts,
        warpBuiltinRulesEnabled = settings.warpBuiltinRulesEnabled,
        warpProfileId = settings.warpProfileId.ifBlank { DefaultWarpProfileId },
        warpAccountKind = normalizeWarpAccountKind(settings.warpAccountKind),
        warpZeroTrustOrg = settings.warpZeroTrustOrg,
        warpSetupState = normalizeWarpSetupState(settings.warpSetupState),
        warpLastScannerMode = normalizeWarpScannerMode(settings.warpLastScannerMode),
        warpEndpointSelectionMode = normalizeWarpEndpointSelectionMode(settings.warpEndpointSelectionMode),
        warpManualEndpointHost = settings.warpManualEndpointHost,
        warpManualEndpointV4 = settings.warpManualEndpointV4,
        warpManualEndpointV6 = settings.warpManualEndpointV6,
        warpManualEndpointPort = settings.warpManualEndpointPort.takeIf { it > 0 } ?: DefaultWarpManualEndpointPort,
        warpScannerEnabled = settings.warpScannerEnabled,
        warpScannerParallelism = settings.warpScannerParallelism.takeIf { it > 0 } ?: DefaultWarpScannerParallelism,
        warpScannerMaxRttMs = settings.warpScannerMaxRttMs.takeIf { it > 0 } ?: DefaultWarpScannerMaxRttMs,
        warpAmneziaEnabled = settings.warpAmneziaEnabled,
        warpAmneziaJc = settings.warpAmneziaJc,
        warpAmneziaJmin = settings.warpAmneziaJmin,
        warpAmneziaJmax = settings.warpAmneziaJmax,
        warpAmneziaH1 = settings.warpAmneziaH1,
        warpAmneziaH2 = settings.warpAmneziaH2,
        warpAmneziaH3 = settings.warpAmneziaH3,
        warpAmneziaH4 = settings.warpAmneziaH4,
        warpAmneziaS1 = settings.warpAmneziaS1,
        warpAmneziaS2 = settings.warpAmneziaS2,
        warpAmneziaS3 = settings.warpAmneziaS3,
        warpAmneziaS4 = settings.warpAmneziaS4,
        warpAmneziaPreset = inferWarpAmneziaPreset(settings.warpAmneziaPreset, rawWarpAmneziaSettings(settings)),
    )

@Suppress("LongMethod")
private fun AppSettingsSnapshot.withRelaySnapshot(settings: AppSettings): AppSettingsSnapshot =
    copy(
        relayEnabled = settings.relayEnabled,
        relayKind = normalizeRelayKind(settings.relayKind),
        relayProfileId = settings.relayProfileId.ifBlank { DefaultRelayProfileId },
        relayOutboundBindIp = settings.relayOutboundBindIp,
        relayServer = settings.relayServer,
        relayServerPort = settings.relayServerPort.takeIf { it > 0 } ?: DefaultHttpsPort,
        relayServerName = settings.relayServerName,
        relayRealityPublicKey = settings.relayRealityPublicKey,
        relayRealityShortId = settings.relayRealityShortId,
        relayVlessTransport = normalizeRelayVlessTransport(settings.relayVlessTransport, settings.relayKind),
        relayXhttpPath = settings.relayXhttpPath,
        relayXhttpHost = settings.relayXhttpHost,
        relayCloudflareTunnelMode = normalizeRelayCloudflareTunnelMode(settings.relayCloudflareTunnelMode),
        relayCloudflarePublishLocalOriginUrl = settings.relayCloudflarePublishLocalOriginUrl,
        relayCloudflareCredentialsRef = settings.relayCloudflareCredentialsRef,
        relayChainEntryServer = settings.relayChainEntryServer,
        relayChainEntryPort = settings.relayChainEntryPort.takeIf { it > 0 } ?: DefaultHttpsPort,
        relayChainEntryServerName = settings.relayChainEntryServerName,
        relayChainEntryPublicKey = settings.relayChainEntryPublicKey,
        relayChainEntryShortId = settings.relayChainEntryShortId,
        relayChainEntryProfileId = settings.relayChainEntryProfileId,
        relayChainExitServer = settings.relayChainExitServer,
        relayChainExitPort = settings.relayChainExitPort.takeIf { it > 0 } ?: DefaultHttpsPort,
        relayChainExitServerName = settings.relayChainExitServerName,
        relayChainExitPublicKey = settings.relayChainExitPublicKey,
        relayChainExitShortId = settings.relayChainExitShortId,
        relayChainExitProfileId = settings.relayChainExitProfileId,
        relayMasqueUrl = settings.relayMasqueUrl,
        relayMasqueUseHttp2Fallback = settings.relayMasqueUseHttp2Fallback,
        relayMasqueCloudflareGeohashEnabled = settings.relayMasqueCloudflareGeohashEnabled,
        relayTuicZeroRtt = settings.relayTuicZeroRtt,
        relayTuicCongestionControl = normalizeRelayCongestionControl(settings.relayTuicCongestionControl),
        relayShadowTlsInnerProfileId = settings.relayShadowtlsInnerProfileId,
        relayNaivePath = settings.relayNaivePath,
        relayAppsScriptScriptIds = normalizeRelayStringList(settings.relayAppsScriptScriptIdsList),
        relayAppsScriptGoogleIp = settings.relayAppsScriptGoogleIp.trim(),
        relayAppsScriptFrontDomain = settings.relayAppsScriptFrontDomain.trim(),
        relayAppsScriptSniHosts = normalizeRelayStringList(settings.relayAppsScriptSniHostsList),
        relayAppsScriptVerifySsl = settings.relayAppsScriptVerifySsl,
        relayAppsScriptParallelRelay = settings.relayAppsScriptParallelRelay,
        relayAppsScriptDirectHosts = normalizeRelayStringList(settings.relayAppsScriptDirectHostsList),
        relayLocalSocksHost = settings.relayLocalSocksHost.ifBlank { DefaultRelayLocalSocksHost },
        relayLocalSocksPort = settings.relayLocalSocksPort.takeIf { it > 0 } ?: DefaultRelayLocalSocksPort,
        relayUdpEnabled = settings.relayUdpEnabled,
        relayTcpFallbackEnabled = settings.relayTcpFallbackEnabled,
        relayFinalmaskType = normalizeRelayFinalmaskType(settings.relayFinalmaskType),
        relayFinalmaskHeaderHex = settings.relayFinalmaskHeaderHex,
        relayFinalmaskTrailerHex = settings.relayFinalmaskTrailerHex,
        relayFinalmaskRandRange = settings.relayFinalmaskRandRange,
        relayFinalmaskSudokuSeed = settings.relayFinalmaskSudokuSeed,
        relayFinalmaskFragmentPackets = settings.relayFinalmaskFragmentPackets,
        relayFinalmaskFragmentMinBytes = settings.relayFinalmaskFragmentMinBytes,
        relayFinalmaskFragmentMaxBytes = settings.relayFinalmaskFragmentMaxBytes,
    )

private fun AppSettingsSnapshot.withRoutingSnapshot(settings: AppSettings): AppSettingsSnapshot =
    copy(
        appRoutingPolicyMode = normalizeAppRoutingPolicyMode(settings.appRoutingPolicyMode),
        appRoutingEnabledPresetIds = settings.effectiveAppRoutingEnabledPresetIds(),
        antiCorrelationEnabled = settings.antiCorrelationEnabled,
        dhtMitigationMode = normalizeDhtMitigationMode(settings.dhtMitigationMode),
    )

private fun AppSettingsSnapshot.withUiRuntimeSnapshot(settings: AppSettings): AppSettingsSnapshot =
    copy(
        onboardingComplete = settings.onboardingComplete,
        webrtcProtectionEnabled = settings.webrtcProtectionEnabled,
        biometricEnabled = settings.biometricEnabled,
        appIconVariant = settings.appIconVariant,
        appIconStyle = settings.appIconStyle,
    )

private fun AppSettings.effectiveWsTunnelMode(): String =
    wsTunnelMode.ifEmpty { if (wsTunnelEnabled) "always" else "off" }
