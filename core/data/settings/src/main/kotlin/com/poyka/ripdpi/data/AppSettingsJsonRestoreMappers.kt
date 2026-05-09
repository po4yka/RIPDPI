package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proto.StrategyTcpStep
import com.poyka.ripdpi.proto.StrategyUdpStep

private const val AppSettingsJsonFormatVersion = 1
private const val DefaultHttpsPort = 443

internal fun AppSettingsSnapshot.toAppSettings(): AppSettings {
    require(formatVersion == AppSettingsJsonFormatVersion) {
        "Unsupported app settings format version: $formatVersion"
    }

    val activeDns = toActiveDnsSettings()
    return AppSettings
        .newBuilder()
        .applyRootAndDnsSnapshot(this, activeDns)
        .applyProxyDesyncSnapshot(this)
        .applyQuicAdaptiveSnapshot(this)
        .applyWarpSnapshot(this)
        .applyRelaySnapshot(this)
        .applyRoutingSnapshot(this)
        .applyChainSnapshots(this)
        .build()
}

private fun AppSettingsSnapshot.toActiveDnsSettings(): ActiveDnsSettings =
    activeDnsSettings(
        dnsMode = dnsMode,
        dnsProviderId = dnsProviderId,
        dnsIp = dnsIp,
        encryptedDnsProtocol = encryptedDnsProtocol,
        encryptedDnsHost = encryptedDnsHost,
        encryptedDnsPort = encryptedDnsPort,
        encryptedDnsTlsServerName = encryptedDnsTlsServerName,
        encryptedDnsBootstrapIps = encryptedDnsBootstrapIps,
        encryptedDnsDohUrl = encryptedDnsDohUrl,
        encryptedDnsDnscryptProviderName = encryptedDnsDnscryptProviderName,
        encryptedDnsDnscryptPublicKey = encryptedDnsDnscryptPublicKey,
    )

private fun AppSettings.Builder.applyRootAndDnsSnapshot(
    snapshot: AppSettingsSnapshot,
    activeDns: ActiveDnsSettings,
): AppSettings.Builder =
    setAppTheme(snapshot.appTheme)
        .setRipdpiMode(snapshot.mode.preferenceValue)
        .setDnsIp(activeDns.dnsIp)
        .setDnsMode(activeDns.mode)
        .setDnsProviderId(activeDns.providerId)
        .setEncryptedDnsProtocol(activeDns.encryptedDnsProtocol)
        .setEncryptedDnsHost(activeDns.encryptedDnsHost)
        .setEncryptedDnsPort(activeDns.encryptedDnsPort)
        .setEncryptedDnsTlsServerName(activeDns.encryptedDnsTlsServerName)
        .clearEncryptedDnsBootstrapIps()
        .addAllEncryptedDnsBootstrapIps(activeDns.encryptedDnsBootstrapIps)
        .setEncryptedDnsDohUrl(activeDns.encryptedDnsDohUrl)
        .setEncryptedDnsDnscryptProviderName(activeDns.encryptedDnsDnscryptProviderName)
        .setEncryptedDnsDnscryptPublicKey(activeDns.encryptedDnsDnscryptPublicKey)
        .setIpv6Enable(snapshot.ipv6Enabled)
        .setEnableCmdSettings(snapshot.enableCommandLineSettings)
        .setCmdArgs(snapshot.commandLineArgs)
        .setOnboardingComplete(snapshot.onboardingComplete)
        .setWebrtcProtectionEnabled(snapshot.webrtcProtectionEnabled)
        .setBiometricEnabled(snapshot.biometricEnabled)
        .setAppIconVariant(snapshot.appIconVariant)
        .setAppIconStyle(snapshot.appIconStyle)

private fun AppSettings.Builder.applyProxyDesyncSnapshot(snapshot: AppSettingsSnapshot): AppSettings.Builder =
    setProxyIp(snapshot.proxyIp)
        .setProxyPort(snapshot.proxyPort)
        .setMaxConnections(snapshot.maxConnections)
        .setBufferSize(snapshot.bufferSize)
        .setNoDomain(snapshot.noDomain)
        .setTcpFastOpen(snapshot.tcpFastOpen)
        .setDefaultTtl(snapshot.defaultTtl)
        .setCustomTtl(snapshot.customTtl)
        .setFakeTtl(snapshot.fakeTtl)
        .setAdaptiveFakeTtlEnabled(snapshot.adaptiveFakeTtlEnabled)
        .setAdaptiveFakeTtlDelta(normalizeAdaptiveFakeTtlDelta(snapshot.adaptiveFakeTtlDelta))
        .setAdaptiveFakeTtlMin(normalizeAdaptiveFakeTtlMin(snapshot.adaptiveFakeTtlMin))
        .setAdaptiveFakeTtlMax(
            normalizeAdaptiveFakeTtlMax(
                snapshot.adaptiveFakeTtlMax,
                normalizeAdaptiveFakeTtlMin(snapshot.adaptiveFakeTtlMin),
            ),
        ).setAdaptiveFakeTtlFallback(
            normalizeAdaptiveFakeTtlFallback(
                snapshot.adaptiveFakeTtlFallback,
                snapshot.fakeTtl.takeIf { it > 0 } ?: DefaultAdaptiveFakeTtlFallback,
            ),
        ).setFakeSni(snapshot.fakeSni)
        .setFakeOffset(snapshot.fakeOffset)
        .setFakeOffsetMarker(snapshot.fakeOffsetMarker)
        .setFakeTlsSource(normalizeFakeTlsSource(snapshot.fakeTlsSource))
        .setFakeTlsSecondaryProfile(snapshot.fakeTlsSecondaryProfile)
        .setFakeTcpTimestampEnabled(snapshot.fakeTcpTimestampEnabled)
        .setFakeTcpTimestampDeltaTicks(snapshot.fakeTcpTimestampDeltaTicks)
        .setFakeTlsUseOriginal(snapshot.fakeTlsUseOriginal)
        .setFakeTlsRandomize(snapshot.fakeTlsRandomize)
        .setFakeTlsDupSessionId(snapshot.fakeTlsDupSessionId)
        .setFakeTlsPadEncap(snapshot.fakeTlsPadEncap)
        .setFakeTlsSize(snapshot.fakeTlsSize)
        .setFakeTlsSniMode(normalizeFakeTlsSniMode(snapshot.fakeTlsSniMode))
        .setIpIdMode(normalizeIpIdMode(snapshot.ipIdMode))
        .setHttpFakeProfile(normalizeHttpFakeProfile(snapshot.httpFakeProfile))
        .setTlsFakeProfile(normalizeTlsFakeProfile(snapshot.tlsFakeProfile))
        .setOobData(snapshot.oobData)
        .setDropSack(snapshot.dropSack)
        .setDesyncHttp(snapshot.desyncHttp)
        .setDesyncHttps(snapshot.desyncHttps)
        .setDesyncUdp(snapshot.desyncUdp)
        .setHostsMode(snapshot.hostsMode)
        .setHostsBlacklist(snapshot.hostsBlacklist)
        .setHostsWhitelist(snapshot.hostsWhitelist)
        .setUdpFakeProfile(normalizeUdpFakeProfile(snapshot.udpFakeProfile))
        .setHostMixedCase(snapshot.hostMixedCase)
        .setDomainMixedCase(snapshot.domainMixedCase)
        .setHostRemoveSpaces(snapshot.hostRemoveSpaces)
        .setHttpMethodEol(snapshot.httpMethodEol)
        .setHttpUnixEol(snapshot.httpUnixEol)
        .setHttpMethodSpace(snapshot.httpMethodSpace)
        .setHttpHostPad(snapshot.httpHostPad)
        .setDesyncAnyProtocol(snapshot.desyncAnyProtocol)

private fun AppSettings.Builder.applyQuicAdaptiveSnapshot(snapshot: AppSettingsSnapshot): AppSettings.Builder =
    setQuicInitialMode(snapshot.quicInitialMode)
        .setQuicSupportV1(snapshot.quicSupportV1)
        .setQuicSupportV2(snapshot.quicSupportV2)
        .setQuicFakeProfile(normalizeQuicFakeProfile(snapshot.quicFakeProfile))
        .setQuicFakeHost(normalizeQuicFakeHost(snapshot.quicFakeHost))
        .setQuicBindLowPort(snapshot.quicBindLowPort)
        .setQuicMigrateAfterHandshake(snapshot.quicMigrateAfterHandshake)
        .setHostAutolearnEnabled(snapshot.hostAutolearnEnabled)
        .setHostAutolearnPenaltyTtlHours(normalizeHostAutolearnPenaltyTtlHours(snapshot.hostAutolearnPenaltyTtlHours))
        .setHostAutolearnMaxHosts(normalizeHostAutolearnMaxHosts(snapshot.hostAutolearnMaxHosts))
        .setNetworkStrategyMemoryEnabled(snapshot.networkStrategyMemoryEnabled)
        .setStrategyEvolution(snapshot.strategyEvolution)
        .setEvolutionEpsilon(snapshot.evolutionEpsilon.coerceIn(0.0, 1.0))
        .setEvolutionExperimentTtlMs(snapshot.evolutionExperimentTtlMs.coerceAtLeast(0))
        .setEvolutionDecayHalfLifeMs(snapshot.evolutionDecayHalfLifeMs.coerceAtLeast(0))
        .setEvolutionCooldownAfterFailures(snapshot.evolutionCooldownAfterFailures.coerceAtLeast(0))
        .setEvolutionCooldownMs(snapshot.evolutionCooldownMs.coerceAtLeast(0))
        .setEntropyPaddingTargetPermil(snapshot.entropyPaddingTargetPermil.coerceAtLeast(0))
        .setEntropyPaddingMax(snapshot.entropyPaddingMax.takeIf { it > 0 } ?: DefaultEntropyPaddingMax)
        .setEntropyMode(entropyModeToProto(snapshot.entropyMode))
        .setShannonEntropyTargetPermil(snapshot.shannonEntropyTargetPermil.coerceAtLeast(0))
        .setTlsFingerprintProfile(normalizeTlsFingerprintProfile(snapshot.tlsFingerprintProfile))
        .setStrategyPackChannel(normalizeStrategyPackChannel(snapshot.strategyPackChannel))
        .setStrategyPackPinnedId(snapshot.strategyPackPinnedId)
        .setStrategyPackPinnedVersion(snapshot.strategyPackPinnedVersion)
        .setStrategyPackRefreshPolicy(normalizeStrategyPackRefreshPolicy(snapshot.strategyPackRefreshPolicy))
        .setStrategyPackAllowRollbackOverride(snapshot.strategyPackAllowRollbackOverride)
        .setAdaptiveFallbackEnabled(snapshot.adaptiveFallbackEnabled)
        .setAdaptiveFallbackTorst(snapshot.adaptiveFallbackTorst)
        .setAdaptiveFallbackTlsErr(snapshot.adaptiveFallbackTlsErr)
        .setAdaptiveFallbackHttpRedirect(snapshot.adaptiveFallbackHttpRedirect)
        .setAdaptiveFallbackConnectFailure(snapshot.adaptiveFallbackConnectFailure)
        .setAdaptiveFallbackAutoSort(snapshot.adaptiveFallbackAutoSort)
        .setAdaptiveFallbackCacheTtlSeconds(
            normalizeAdaptiveFallbackCacheTtlSeconds(snapshot.adaptiveFallbackCacheTtlSeconds),
        ).setAdaptiveFallbackCachePrefixV4(
            normalizeAdaptiveFallbackCachePrefixV4(snapshot.adaptiveFallbackCachePrefixV4),
        ).setWsTunnelEnabled(snapshot.wsTunnelEnabled)
        .setWsTunnelMode(snapshot.wsTunnelMode)

@Suppress("LongMethod")
private fun AppSettings.Builder.applyWarpSnapshot(snapshot: AppSettingsSnapshot): AppSettings.Builder =
    setWarpEnabled(snapshot.warpEnabled)
        .setWarpRouteMode(normalizeWarpRouteMode(snapshot.warpRouteMode))
        .setWarpRouteHosts(snapshot.warpRouteHosts)
        .setWarpBuiltinRulesEnabled(snapshot.warpBuiltinRulesEnabled)
        .setWarpProfileId(snapshot.warpProfileId.ifBlank { DefaultWarpProfileId })
        .setWarpAccountKind(normalizeWarpAccountKind(snapshot.warpAccountKind))
        .setWarpZeroTrustOrg(snapshot.warpZeroTrustOrg)
        .setWarpSetupState(normalizeWarpSetupState(snapshot.warpSetupState))
        .setWarpLastScannerMode(normalizeWarpScannerMode(snapshot.warpLastScannerMode))
        .setWarpEndpointSelectionMode(normalizeWarpEndpointSelectionMode(snapshot.warpEndpointSelectionMode))
        .setWarpManualEndpointHost(snapshot.warpManualEndpointHost)
        .setWarpManualEndpointV4(snapshot.warpManualEndpointV4)
        .setWarpManualEndpointV6(snapshot.warpManualEndpointV6)
        .setWarpManualEndpointPort(snapshot.warpManualEndpointPort.takeIf { it > 0 } ?: DefaultWarpManualEndpointPort)
        .setWarpScannerEnabled(snapshot.warpScannerEnabled)
        .setWarpScannerParallelism(snapshot.warpScannerParallelism.takeIf { it > 0 } ?: DefaultWarpScannerParallelism)
        .setWarpScannerMaxRttMs(snapshot.warpScannerMaxRttMs.takeIf { it > 0 } ?: DefaultWarpScannerMaxRttMs)
        .setWarpAmneziaEnabled(snapshot.warpAmneziaEnabled)
        .setWarpAmneziaJc(snapshot.warpAmneziaJc)
        .setWarpAmneziaJmin(snapshot.warpAmneziaJmin)
        .setWarpAmneziaJmax(snapshot.warpAmneziaJmax)
        .setWarpAmneziaH1(snapshot.warpAmneziaH1)
        .setWarpAmneziaH2(snapshot.warpAmneziaH2)
        .setWarpAmneziaH3(snapshot.warpAmneziaH3)
        .setWarpAmneziaH4(snapshot.warpAmneziaH4)
        .setWarpAmneziaS1(snapshot.warpAmneziaS1)
        .setWarpAmneziaS2(snapshot.warpAmneziaS2)
        .setWarpAmneziaS3(snapshot.warpAmneziaS3)
        .setWarpAmneziaS4(snapshot.warpAmneziaS4)
        .setWarpAmneziaPreset(
            inferWarpAmneziaPreset(
                snapshot.warpAmneziaPreset,
                WarpAmneziaSettings(
                    enabled = snapshot.warpAmneziaEnabled,
                    jc = snapshot.warpAmneziaJc,
                    jmin = snapshot.warpAmneziaJmin,
                    jmax = snapshot.warpAmneziaJmax,
                    h1 = snapshot.warpAmneziaH1,
                    h2 = snapshot.warpAmneziaH2,
                    h3 = snapshot.warpAmneziaH3,
                    h4 = snapshot.warpAmneziaH4,
                    s1 = snapshot.warpAmneziaS1,
                    s2 = snapshot.warpAmneziaS2,
                    s3 = snapshot.warpAmneziaS3,
                    s4 = snapshot.warpAmneziaS4,
                ),
            ),
        )

@Suppress("LongMethod")
private fun AppSettings.Builder.applyRelaySnapshot(snapshot: AppSettingsSnapshot): AppSettings.Builder =
    setRelayEnabled(snapshot.relayEnabled)
        .setRelayKind(normalizeRelayKind(snapshot.relayKind))
        .setRelayProfileId(snapshot.relayProfileId.ifBlank { DefaultRelayProfileId })
        .setRelayOutboundBindIp(snapshot.relayOutboundBindIp)
        .setRelayServer(snapshot.relayServer)
        .setRelayServerPort(snapshot.relayServerPort.takeIf { it > 0 } ?: DefaultHttpsPort)
        .setRelayServerName(snapshot.relayServerName)
        .setRelayRealityPublicKey(snapshot.relayRealityPublicKey)
        .setRelayRealityShortId(snapshot.relayRealityShortId)
        .setRelayVlessTransport(normalizeRelayVlessTransport(snapshot.relayVlessTransport, snapshot.relayKind))
        .setRelayXhttpPath(snapshot.relayXhttpPath)
        .setRelayXhttpHost(snapshot.relayXhttpHost)
        .setRelayCloudflareTunnelMode(normalizeRelayCloudflareTunnelMode(snapshot.relayCloudflareTunnelMode))
        .setRelayCloudflarePublishLocalOriginUrl(snapshot.relayCloudflarePublishLocalOriginUrl)
        .setRelayCloudflareCredentialsRef(snapshot.relayCloudflareCredentialsRef)
        .setRelayChainEntryServer(snapshot.relayChainEntryServer)
        .setRelayChainEntryPort(snapshot.relayChainEntryPort.takeIf { it > 0 } ?: DefaultHttpsPort)
        .setRelayChainEntryServerName(snapshot.relayChainEntryServerName)
        .setRelayChainEntryPublicKey(snapshot.relayChainEntryPublicKey)
        .setRelayChainEntryShortId(snapshot.relayChainEntryShortId)
        .setRelayChainEntryProfileId(snapshot.relayChainEntryProfileId)
        .setRelayChainExitServer(snapshot.relayChainExitServer)
        .setRelayChainExitPort(snapshot.relayChainExitPort.takeIf { it > 0 } ?: DefaultHttpsPort)
        .setRelayChainExitServerName(snapshot.relayChainExitServerName)
        .setRelayChainExitPublicKey(snapshot.relayChainExitPublicKey)
        .setRelayChainExitShortId(snapshot.relayChainExitShortId)
        .setRelayChainExitProfileId(snapshot.relayChainExitProfileId)
        .setRelayMasqueUrl(snapshot.relayMasqueUrl)
        .setRelayMasqueUseHttp2Fallback(snapshot.relayMasqueUseHttp2Fallback)
        .setRelayMasqueCloudflareGeohashEnabled(snapshot.relayMasqueCloudflareGeohashEnabled)
        .setRelayTuicZeroRtt(snapshot.relayTuicZeroRtt)
        .setRelayTuicCongestionControl(normalizeRelayCongestionControl(snapshot.relayTuicCongestionControl))
        .setRelayShadowtlsInnerProfileId(snapshot.relayShadowTlsInnerProfileId)
        .setRelayNaivePath(snapshot.relayNaivePath)
        .clearRelayAppsScriptScriptIds()
        .addAllRelayAppsScriptScriptIds(normalizeRelayStringList(snapshot.relayAppsScriptScriptIds))
        .setRelayAppsScriptGoogleIp(snapshot.relayAppsScriptGoogleIp.trim())
        .setRelayAppsScriptFrontDomain(snapshot.relayAppsScriptFrontDomain.trim())
        .clearRelayAppsScriptSniHosts()
        .addAllRelayAppsScriptSniHosts(normalizeRelayStringList(snapshot.relayAppsScriptSniHosts))
        .setRelayAppsScriptVerifySsl(snapshot.relayAppsScriptVerifySsl)
        .setRelayAppsScriptParallelRelay(snapshot.relayAppsScriptParallelRelay)
        .clearRelayAppsScriptDirectHosts()
        .addAllRelayAppsScriptDirectHosts(normalizeRelayStringList(snapshot.relayAppsScriptDirectHosts))
        .setRelayLocalSocksHost(snapshot.relayLocalSocksHost.ifBlank { DefaultRelayLocalSocksHost })
        .setRelayLocalSocksPort(snapshot.relayLocalSocksPort.takeIf { it > 0 } ?: DefaultRelayLocalSocksPort)
        .setRelayUdpEnabled(snapshot.relayUdpEnabled)
        .setRelayTcpFallbackEnabled(snapshot.relayTcpFallbackEnabled)
        .setRelayFinalmaskType(normalizeRelayFinalmaskType(snapshot.relayFinalmaskType))
        .setRelayFinalmaskHeaderHex(snapshot.relayFinalmaskHeaderHex)
        .setRelayFinalmaskTrailerHex(snapshot.relayFinalmaskTrailerHex)
        .setRelayFinalmaskRandRange(snapshot.relayFinalmaskRandRange)
        .setRelayFinalmaskSudokuSeed(snapshot.relayFinalmaskSudokuSeed)
        .setRelayFinalmaskFragmentPackets(snapshot.relayFinalmaskFragmentPackets)
        .setRelayFinalmaskFragmentMinBytes(snapshot.relayFinalmaskFragmentMinBytes)
        .setRelayFinalmaskFragmentMaxBytes(snapshot.relayFinalmaskFragmentMaxBytes)

private fun AppSettings.Builder.applyRoutingSnapshot(snapshot: AppSettingsSnapshot): AppSettings.Builder =
    setAppRoutingPolicyMode(normalizeAppRoutingPolicyMode(snapshot.appRoutingPolicyMode))
        .clearAppRoutingEnabledPresetIds()
        .addAllAppRoutingEnabledPresetIds(
            snapshot.appRoutingEnabledPresetIds
                .map(String::trim)
                .filter(String::isNotEmpty),
        ).setAntiCorrelationEnabled(snapshot.antiCorrelationEnabled)
        .setDhtMitigationMode(normalizeDhtMitigationMode(snapshot.dhtMitigationMode))
        .setGroupActivationFilterCompat(normalizeActivationFilter(snapshot.groupActivationFilter))

private fun AppSettings.Builder.applyChainSnapshots(snapshot: AppSettingsSnapshot): AppSettings.Builder =
    also { builder ->
        snapshot.tcpChainSteps.forEach { step -> builder.addTcpChainStepSnapshot(step) }
        snapshot.udpChainSteps.forEach { step -> builder.addUdpChainStepSnapshot(step) }
    }

private fun AppSettings.Builder.addTcpChainStepSnapshot(step: AppSettingsTcpChainSnapshot) {
    addTcpChainSteps(
        StrategyTcpStep
            .newBuilder()
            .setKind(step.kind)
            .setMarker(step.marker)
            .setMidhostMarker(step.midhostMarker.orEmpty())
            .setFakeHostTemplate(step.fakeHostTemplate.orEmpty())
            .setOverlapSize(step.overlapSize ?: 0)
            .setFakeMode(step.fakeMode.orEmpty())
            .setFragmentCount(step.fragmentCount)
            .setMinFragmentSize(step.minFragmentSize)
            .setMaxFragmentSize(step.maxFragmentSize)
            .setIpv6ExtensionProfile(normalizeStrategyIpv6ExtensionProfile(step.ipv6ExtensionProfile))
            .setTcpFlagsSet(step.tcpFlagsSet.orEmpty())
            .setTcpFlagsUnset(step.tcpFlagsUnset.orEmpty())
            .setTcpFlagsOrigSet(step.tcpFlagsOrigSet.orEmpty())
            .setTcpFlagsOrigUnset(step.tcpFlagsOrigUnset.orEmpty())
            .apply {
                val normalizedFilter = normalizeActivationFilter(step.activationFilter)
                if (!normalizedFilter.isEmpty) {
                    setActivationFilter(normalizedFilter.toProto())
                }
            }.build(),
    )
}

private fun AppSettings.Builder.addUdpChainStepSnapshot(step: AppSettingsUdpChainSnapshot) {
    addUdpChainSteps(
        StrategyUdpStep
            .newBuilder()
            .setKind(step.kind)
            .setCount(step.count)
            .setSplitBytes(step.splitBytes)
            .setIpv6ExtensionProfile(normalizeStrategyIpv6ExtensionProfile(step.ipv6ExtensionProfile))
            .apply {
                val normalizedFilter = normalizeActivationFilter(step.activationFilter)
                if (!normalizedFilter.isEmpty) {
                    setActivationFilter(normalizedFilter.toProto())
                }
            }.build(),
    )
}
