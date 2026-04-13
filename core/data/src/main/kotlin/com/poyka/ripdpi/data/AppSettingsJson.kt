package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val AppSettingsJsonFormatVersion = 1
private const val DefaultHttpsPort = 443

private val defaultSettings = AppSettingsSerializer.defaultValue

private val appSettingsJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = true
    }

fun AppSettings.toJson(): String = appSettingsJson.encodeToString(toSnapshot())

fun appSettingsFromJson(payload: String): AppSettings =
    appSettingsJson.decodeFromString<AppSettingsSnapshot>(payload).toAppSettings()

private fun AppSettings.effectiveWsTunnelMode(): String =
    wsTunnelMode.ifEmpty { if (wsTunnelEnabled) "always" else "off" }

@Suppress("LongMethod")
private fun AppSettings.toSnapshot(): AppSettingsSnapshot =
    activeDnsSettings().let { activeDns ->
        AppSettingsSnapshot(
            appTheme = appTheme,
            mode = Mode.fromString(ripdpiMode.ifEmpty { defaultSettings.ripdpiMode }),
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
            ipv6Enabled = ipv6Enable,
            enableCommandLineSettings = enableCmdSettings,
            commandLineArgs = cmdArgs,
            proxyIp = proxyIp,
            proxyPort = proxyPort,
            maxConnections = maxConnections,
            bufferSize = bufferSize,
            noDomain = noDomain,
            tcpFastOpen = tcpFastOpen,
            defaultTtl = defaultTtl,
            customTtl = customTtl,
            fakeTtl = fakeTtl,
            adaptiveFakeTtlEnabled = adaptiveFakeTtlEnabled,
            adaptiveFakeTtlDelta = effectiveAdaptiveFakeTtlDelta(),
            adaptiveFakeTtlMin = effectiveAdaptiveFakeTtlMin(),
            adaptiveFakeTtlMax = effectiveAdaptiveFakeTtlMax(),
            adaptiveFakeTtlFallback = effectiveAdaptiveFakeTtlFallback(),
            fakeSni = fakeSni,
            fakeOffset = fakeOffset,
            fakeOffsetMarker = fakeOffsetMarker,
            fakeTlsSource = normalizeFakeTlsSource(fakeTlsSource),
            fakeTlsSecondaryProfile = fakeTlsSecondaryProfile,
            fakeTcpTimestampEnabled = fakeTcpTimestampEnabled,
            fakeTcpTimestampDeltaTicks = fakeTcpTimestampDeltaTicks,
            fakeTlsUseOriginal = fakeTlsUseOriginal,
            fakeTlsRandomize = fakeTlsRandomize,
            fakeTlsDupSessionId = fakeTlsDupSessionId,
            fakeTlsPadEncap = fakeTlsPadEncap,
            fakeTlsSize = fakeTlsSize,
            fakeTlsSniMode = effectiveFakeTlsSniMode(),
            httpFakeProfile = effectiveHttpFakeProfile(),
            tlsFakeProfile = effectiveTlsFakeProfile(),
            oobData = oobData,
            dropSack = dropSack,
            desyncHttp = desyncHttp,
            desyncHttps = desyncHttps,
            desyncUdp = desyncUdp,
            hostsMode = hostsMode,
            hostsBlacklist = hostsBlacklist,
            hostsWhitelist = hostsWhitelist,
            udpFakeProfile = effectiveUdpFakeProfile(),
            hostMixedCase = hostMixedCase,
            domainMixedCase = domainMixedCase,
            hostRemoveSpaces = hostRemoveSpaces,
            httpMethodEol = httpMethodEol,
            httpUnixEol = httpUnixEol,
            httpMethodSpace = httpMethodSpace,
            httpHostPad = httpHostPad,
            onboardingComplete = onboardingComplete,
            webrtcProtectionEnabled = webrtcProtectionEnabled,
            biometricEnabled = biometricEnabled,
            appIconVariant = appIconVariant,
            appIconStyle = appIconStyle,
            tcpChainSteps =
                tcpChainStepsList.map {
                    AppSettingsTcpChainSnapshot(
                        kind = it.kind,
                        marker = it.marker,
                        midhostMarker = it.midhostMarker.takeIf(String::isNotBlank),
                        fakeHostTemplate = it.fakeHostTemplate.takeIf(String::isNotBlank),
                        overlapSize = it.overlapSize.takeIf { value -> value > 0 },
                        fakeMode =
                            it.fakeMode.takeIf { value ->
                                value.isNotBlank() &&
                                    value != SeqOverlapFakeModeProfile
                            },
                        fragmentCount = it.fragmentCount,
                        minFragmentSize = it.minFragmentSize,
                        maxFragmentSize = it.maxFragmentSize,
                        activationFilter =
                            if (it.hasActivationFilter()) it.activationFilter.toModel() else ActivationFilterModel(),
                        ipv6ExtensionProfile = normalizeStrategyIpv6ExtensionProfile(it.ipv6ExtensionProfile),
                        tcpFlagsSet = it.tcpFlagsSet.takeIf(String::isNotBlank),
                        tcpFlagsUnset = it.tcpFlagsUnset.takeIf(String::isNotBlank),
                        tcpFlagsOrigSet = it.tcpFlagsOrigSet.takeIf(String::isNotBlank),
                        tcpFlagsOrigUnset = it.tcpFlagsOrigUnset.takeIf(String::isNotBlank),
                    )
                },
            udpChainSteps =
                udpChainStepsList.map {
                    AppSettingsUdpChainSnapshot(
                        kind = it.kind,
                        count = it.count,
                        splitBytes = it.splitBytes,
                        activationFilter =
                            if (it.hasActivationFilter()) it.activationFilter.toModel() else ActivationFilterModel(),
                        ipv6ExtensionProfile = normalizeStrategyIpv6ExtensionProfile(it.ipv6ExtensionProfile),
                    )
                },
            quicInitialMode = effectiveQuicInitialMode(),
            quicSupportV1 = effectiveQuicSupportV1(),
            quicSupportV2 = effectiveQuicSupportV2(),
            quicFakeProfile = effectiveQuicFakeProfile(),
            quicFakeHost = effectiveQuicFakeHost(),
            quicBindLowPort = quicBindLowPort,
            quicMigrateAfterHandshake = quicMigrateAfterHandshake,
            hostAutolearnEnabled = hostAutolearnEnabled,
            hostAutolearnPenaltyTtlHours = normalizeHostAutolearnPenaltyTtlHours(hostAutolearnPenaltyTtlHours),
            hostAutolearnMaxHosts = normalizeHostAutolearnMaxHosts(hostAutolearnMaxHosts),
            networkStrategyMemoryEnabled = networkStrategyMemoryEnabled,
            strategyEvolution = strategyEvolution,
            evolutionEpsilon = evolutionEpsilon.takeIf { it in 0.0..1.0 } ?: DefaultEvolutionEpsilon,
            entropyPaddingTargetPermil = entropyPaddingTargetPermil.coerceAtLeast(0),
            entropyPaddingMax = entropyPaddingMax.takeIf { it > 0 } ?: DefaultEntropyPaddingMax,
            entropyMode = entropyModeFromProto(entropyMode),
            shannonEntropyTargetPermil = shannonEntropyTargetPermil.coerceAtLeast(0),
            tlsFingerprintProfile = normalizeTlsFingerprintProfile(tlsFingerprintProfile),
            strategyPackChannel = normalizeStrategyPackChannel(strategyPackChannel),
            strategyPackPinnedId = strategyPackPinnedId,
            strategyPackPinnedVersion = strategyPackPinnedVersion,
            strategyPackRefreshPolicy = normalizeStrategyPackRefreshPolicy(strategyPackRefreshPolicy),
            adaptiveFallbackEnabled = adaptiveFallbackEnabled,
            adaptiveFallbackTorst = adaptiveFallbackTorst,
            adaptiveFallbackTlsErr = adaptiveFallbackTlsErr,
            adaptiveFallbackHttpRedirect = adaptiveFallbackHttpRedirect,
            adaptiveFallbackConnectFailure = adaptiveFallbackConnectFailure,
            adaptiveFallbackAutoSort = adaptiveFallbackAutoSort,
            adaptiveFallbackCacheTtlSeconds =
                normalizeAdaptiveFallbackCacheTtlSeconds(adaptiveFallbackCacheTtlSeconds),
            adaptiveFallbackCachePrefixV4 =
                normalizeAdaptiveFallbackCachePrefixV4(adaptiveFallbackCachePrefixV4),
            wsTunnelEnabled = wsTunnelEnabled,
            wsTunnelMode = effectiveWsTunnelMode(),
            warpEnabled = warpEnabled,
            warpRouteMode = normalizeWarpRouteMode(warpRouteMode),
            warpRouteHosts = warpRouteHosts,
            warpBuiltinRulesEnabled = warpBuiltinRulesEnabled,
            warpProfileId = warpProfileId.ifBlank { DefaultWarpProfileId },
            warpAccountKind = normalizeWarpAccountKind(warpAccountKind),
            warpZeroTrustOrg = warpZeroTrustOrg,
            warpSetupState = normalizeWarpSetupState(warpSetupState),
            warpLastScannerMode = normalizeWarpScannerMode(warpLastScannerMode),
            warpEndpointSelectionMode = normalizeWarpEndpointSelectionMode(warpEndpointSelectionMode),
            warpManualEndpointHost = warpManualEndpointHost,
            warpManualEndpointV4 = warpManualEndpointV4,
            warpManualEndpointV6 = warpManualEndpointV6,
            warpManualEndpointPort = warpManualEndpointPort.takeIf { it > 0 } ?: DefaultWarpManualEndpointPort,
            warpScannerEnabled = warpScannerEnabled,
            warpScannerParallelism = warpScannerParallelism.takeIf { it > 0 } ?: DefaultWarpScannerParallelism,
            warpScannerMaxRttMs = warpScannerMaxRttMs.takeIf { it > 0 } ?: DefaultWarpScannerMaxRttMs,
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
            warpAmneziaPreset =
                inferWarpAmneziaPreset(
                    warpAmneziaPreset,
                    rawWarpAmneziaSettings(this),
                ),
            relayEnabled = relayEnabled,
            relayKind = normalizeRelayKind(relayKind),
            relayProfileId = relayProfileId.ifBlank { DefaultRelayProfileId },
            relayOutboundBindIp = relayOutboundBindIp,
            relayServer = relayServer,
            relayServerPort = relayServerPort.takeIf { it > 0 } ?: DefaultHttpsPort,
            relayServerName = relayServerName,
            relayRealityPublicKey = relayRealityPublicKey,
            relayRealityShortId = relayRealityShortId,
            relayVlessTransport = normalizeRelayVlessTransport(relayVlessTransport, relayKind),
            relayXhttpPath = relayXhttpPath,
            relayXhttpHost = relayXhttpHost,
            relayCloudflareTunnelMode = normalizeRelayCloudflareTunnelMode(relayCloudflareTunnelMode),
            relayCloudflarePublishLocalOriginUrl = relayCloudflarePublishLocalOriginUrl,
            relayCloudflareCredentialsRef = relayCloudflareCredentialsRef,
            relayChainEntryServer = relayChainEntryServer,
            relayChainEntryPort = relayChainEntryPort.takeIf { it > 0 } ?: 443,
            relayChainEntryServerName = relayChainEntryServerName,
            relayChainEntryPublicKey = relayChainEntryPublicKey,
            relayChainEntryShortId = relayChainEntryShortId,
            relayChainEntryProfileId = relayChainEntryProfileId,
            relayChainExitServer = relayChainExitServer,
            relayChainExitPort = relayChainExitPort.takeIf { it > 0 } ?: 443,
            relayChainExitServerName = relayChainExitServerName,
            relayChainExitPublicKey = relayChainExitPublicKey,
            relayChainExitShortId = relayChainExitShortId,
            relayChainExitProfileId = relayChainExitProfileId,
            relayMasqueUrl = relayMasqueUrl,
            relayMasqueUseHttp2Fallback = relayMasqueUseHttp2Fallback,
            relayMasqueCloudflareGeohashEnabled = relayMasqueCloudflareGeohashEnabled,
            relayTuicZeroRtt = relayTuicZeroRtt,
            relayTuicCongestionControl = normalizeRelayCongestionControl(relayTuicCongestionControl),
            relayShadowTlsInnerProfileId = relayShadowtlsInnerProfileId,
            relayNaivePath = relayNaivePath,
            relayLocalSocksHost = relayLocalSocksHost.ifBlank { DefaultRelayLocalSocksHost },
            relayLocalSocksPort = relayLocalSocksPort.takeIf { it > 0 } ?: DefaultRelayLocalSocksPort,
            relayUdpEnabled = relayUdpEnabled,
            relayTcpFallbackEnabled = relayTcpFallbackEnabled,
            relayFinalmaskType = normalizeRelayFinalmaskType(relayFinalmaskType),
            relayFinalmaskHeaderHex = relayFinalmaskHeaderHex,
            relayFinalmaskTrailerHex = relayFinalmaskTrailerHex,
            relayFinalmaskRandRange = relayFinalmaskRandRange,
            relayFinalmaskSudokuSeed = relayFinalmaskSudokuSeed,
            relayFinalmaskFragmentPackets = relayFinalmaskFragmentPackets,
            relayFinalmaskFragmentMinBytes = relayFinalmaskFragmentMinBytes,
            relayFinalmaskFragmentMaxBytes = relayFinalmaskFragmentMaxBytes,
            desyncAnyProtocol = desyncAnyProtocol,
            appRoutingPolicyMode = normalizeAppRoutingPolicyMode(appRoutingPolicyMode),
            appRoutingEnabledPresetIds = effectiveAppRoutingEnabledPresetIds(),
            antiCorrelationEnabled = antiCorrelationEnabled,
            dhtMitigationMode = normalizeDhtMitigationMode(dhtMitigationMode),
            groupActivationFilter =
                if (hasGroupActivationFilter()) {
                    groupActivationFilter.toModel().let(
                        ::normalizeActivationFilter,
                    )
                } else {
                    ActivationFilterModel()
                },
        )
    }

@Suppress("LongMethod")
private fun AppSettingsSnapshot.toAppSettings(): AppSettings {
    require(formatVersion == AppSettingsJsonFormatVersion) {
        "Unsupported app settings format version: $formatVersion"
    }

    val activeDns =
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

    return AppSettings
        .newBuilder()
        .setAppTheme(appTheme)
        .setRipdpiMode(mode.preferenceValue)
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
        .setIpv6Enable(ipv6Enabled)
        .setEnableCmdSettings(enableCommandLineSettings)
        .setCmdArgs(commandLineArgs)
        .setProxyIp(proxyIp)
        .setProxyPort(proxyPort)
        .setMaxConnections(maxConnections)
        .setBufferSize(bufferSize)
        .setNoDomain(noDomain)
        .setTcpFastOpen(tcpFastOpen)
        .setDefaultTtl(defaultTtl)
        .setCustomTtl(customTtl)
        .setFakeTtl(fakeTtl)
        .setAdaptiveFakeTtlEnabled(adaptiveFakeTtlEnabled)
        .setAdaptiveFakeTtlDelta(normalizeAdaptiveFakeTtlDelta(adaptiveFakeTtlDelta))
        .setAdaptiveFakeTtlMin(normalizeAdaptiveFakeTtlMin(adaptiveFakeTtlMin))
        .setAdaptiveFakeTtlMax(
            normalizeAdaptiveFakeTtlMax(adaptiveFakeTtlMax, normalizeAdaptiveFakeTtlMin(adaptiveFakeTtlMin)),
        ).setAdaptiveFakeTtlFallback(
            normalizeAdaptiveFakeTtlFallback(
                adaptiveFakeTtlFallback,
                fakeTtl.takeIf { it > 0 } ?: DefaultAdaptiveFakeTtlFallback,
            ),
        ).setFakeSni(fakeSni)
        .setFakeOffset(fakeOffset)
        .setFakeOffsetMarker(fakeOffsetMarker)
        .setFakeTlsSource(normalizeFakeTlsSource(fakeTlsSource))
        .setFakeTlsSecondaryProfile(fakeTlsSecondaryProfile)
        .setFakeTcpTimestampEnabled(fakeTcpTimestampEnabled)
        .setFakeTcpTimestampDeltaTicks(fakeTcpTimestampDeltaTicks)
        .setFakeTlsUseOriginal(fakeTlsUseOriginal)
        .setFakeTlsRandomize(fakeTlsRandomize)
        .setFakeTlsDupSessionId(fakeTlsDupSessionId)
        .setFakeTlsPadEncap(fakeTlsPadEncap)
        .setFakeTlsSize(fakeTlsSize)
        .setFakeTlsSniMode(normalizeFakeTlsSniMode(fakeTlsSniMode))
        .setHttpFakeProfile(normalizeHttpFakeProfile(httpFakeProfile))
        .setTlsFakeProfile(normalizeTlsFakeProfile(tlsFakeProfile))
        .setOobData(oobData)
        .setDropSack(dropSack)
        .setDesyncHttp(desyncHttp)
        .setDesyncHttps(desyncHttps)
        .setDesyncUdp(desyncUdp)
        .setHostsMode(hostsMode)
        .setHostsBlacklist(hostsBlacklist)
        .setHostsWhitelist(hostsWhitelist)
        .setUdpFakeProfile(normalizeUdpFakeProfile(udpFakeProfile))
        .setHostMixedCase(hostMixedCase)
        .setDomainMixedCase(domainMixedCase)
        .setHostRemoveSpaces(hostRemoveSpaces)
        .setHttpMethodEol(httpMethodEol)
        .setHttpUnixEol(httpUnixEol)
        .setHttpMethodSpace(httpMethodSpace)
        .setHttpHostPad(httpHostPad)
        .setOnboardingComplete(onboardingComplete)
        .setWebrtcProtectionEnabled(webrtcProtectionEnabled)
        .setBiometricEnabled(biometricEnabled)
        .setAppIconVariant(appIconVariant)
        .setAppIconStyle(appIconStyle)
        .setQuicInitialMode(quicInitialMode)
        .setQuicSupportV1(quicSupportV1)
        .setQuicSupportV2(quicSupportV2)
        .setQuicFakeProfile(normalizeQuicFakeProfile(quicFakeProfile))
        .setQuicFakeHost(normalizeQuicFakeHost(quicFakeHost))
        .setQuicBindLowPort(quicBindLowPort)
        .setQuicMigrateAfterHandshake(quicMigrateAfterHandshake)
        .setHostAutolearnEnabled(hostAutolearnEnabled)
        .setHostAutolearnPenaltyTtlHours(normalizeHostAutolearnPenaltyTtlHours(hostAutolearnPenaltyTtlHours))
        .setHostAutolearnMaxHosts(normalizeHostAutolearnMaxHosts(hostAutolearnMaxHosts))
        .setNetworkStrategyMemoryEnabled(networkStrategyMemoryEnabled)
        .setStrategyEvolution(strategyEvolution)
        .setEvolutionEpsilon(evolutionEpsilon.coerceIn(0.0, 1.0))
        .setEntropyPaddingTargetPermil(entropyPaddingTargetPermil.coerceAtLeast(0))
        .setEntropyPaddingMax(entropyPaddingMax.takeIf { it > 0 } ?: DefaultEntropyPaddingMax)
        .setEntropyMode(entropyModeToProto(entropyMode))
        .setShannonEntropyTargetPermil(shannonEntropyTargetPermil.coerceAtLeast(0))
        .setTlsFingerprintProfile(normalizeTlsFingerprintProfile(tlsFingerprintProfile))
        .setStrategyPackChannel(normalizeStrategyPackChannel(strategyPackChannel))
        .setStrategyPackPinnedId(strategyPackPinnedId)
        .setStrategyPackPinnedVersion(strategyPackPinnedVersion)
        .setStrategyPackRefreshPolicy(normalizeStrategyPackRefreshPolicy(strategyPackRefreshPolicy))
        .setAdaptiveFallbackEnabled(adaptiveFallbackEnabled)
        .setAdaptiveFallbackTorst(adaptiveFallbackTorst)
        .setAdaptiveFallbackTlsErr(adaptiveFallbackTlsErr)
        .setAdaptiveFallbackHttpRedirect(adaptiveFallbackHttpRedirect)
        .setAdaptiveFallbackConnectFailure(adaptiveFallbackConnectFailure)
        .setAdaptiveFallbackAutoSort(adaptiveFallbackAutoSort)
        .setAdaptiveFallbackCacheTtlSeconds(normalizeAdaptiveFallbackCacheTtlSeconds(adaptiveFallbackCacheTtlSeconds))
        .setAdaptiveFallbackCachePrefixV4(normalizeAdaptiveFallbackCachePrefixV4(adaptiveFallbackCachePrefixV4))
        .setWsTunnelEnabled(wsTunnelEnabled)
        .setWsTunnelMode(wsTunnelMode)
        .setWarpEnabled(warpEnabled)
        .setWarpRouteMode(normalizeWarpRouteMode(warpRouteMode))
        .setWarpRouteHosts(warpRouteHosts)
        .setWarpBuiltinRulesEnabled(warpBuiltinRulesEnabled)
        .setWarpProfileId(warpProfileId.ifBlank { DefaultWarpProfileId })
        .setWarpAccountKind(normalizeWarpAccountKind(warpAccountKind))
        .setWarpZeroTrustOrg(warpZeroTrustOrg)
        .setWarpSetupState(normalizeWarpSetupState(warpSetupState))
        .setWarpLastScannerMode(normalizeWarpScannerMode(warpLastScannerMode))
        .setWarpEndpointSelectionMode(normalizeWarpEndpointSelectionMode(warpEndpointSelectionMode))
        .setWarpManualEndpointHost(warpManualEndpointHost)
        .setWarpManualEndpointV4(warpManualEndpointV4)
        .setWarpManualEndpointV6(warpManualEndpointV6)
        .setWarpManualEndpointPort(warpManualEndpointPort.takeIf { it > 0 } ?: DefaultWarpManualEndpointPort)
        .setWarpScannerEnabled(warpScannerEnabled)
        .setWarpScannerParallelism(warpScannerParallelism.takeIf { it > 0 } ?: DefaultWarpScannerParallelism)
        .setWarpScannerMaxRttMs(warpScannerMaxRttMs.takeIf { it > 0 } ?: DefaultWarpScannerMaxRttMs)
        .setWarpAmneziaEnabled(warpAmneziaEnabled)
        .setWarpAmneziaJc(warpAmneziaJc)
        .setWarpAmneziaJmin(warpAmneziaJmin)
        .setWarpAmneziaJmax(warpAmneziaJmax)
        .setWarpAmneziaH1(warpAmneziaH1)
        .setWarpAmneziaH2(warpAmneziaH2)
        .setWarpAmneziaH3(warpAmneziaH3)
        .setWarpAmneziaH4(warpAmneziaH4)
        .setWarpAmneziaS1(warpAmneziaS1)
        .setWarpAmneziaS2(warpAmneziaS2)
        .setWarpAmneziaS3(warpAmneziaS3)
        .setWarpAmneziaS4(warpAmneziaS4)
        .setWarpAmneziaPreset(
            inferWarpAmneziaPreset(
                warpAmneziaPreset,
                WarpAmneziaSettings(
                    enabled = warpAmneziaEnabled,
                    jc = warpAmneziaJc,
                    jmin = warpAmneziaJmin,
                    jmax = warpAmneziaJmax,
                    h1 = warpAmneziaH1,
                    h2 = warpAmneziaH2,
                    h3 = warpAmneziaH3,
                    h4 = warpAmneziaH4,
                    s1 = warpAmneziaS1,
                    s2 = warpAmneziaS2,
                    s3 = warpAmneziaS3,
                    s4 = warpAmneziaS4,
                ),
            ),
        ).setRelayEnabled(relayEnabled)
        .setRelayKind(normalizeRelayKind(relayKind))
        .setRelayProfileId(relayProfileId.ifBlank { DefaultRelayProfileId })
        .setRelayOutboundBindIp(relayOutboundBindIp)
        .setRelayServer(relayServer)
        .setRelayServerPort(relayServerPort.takeIf { it > 0 } ?: DefaultHttpsPort)
        .setRelayServerName(relayServerName)
        .setRelayRealityPublicKey(relayRealityPublicKey)
        .setRelayRealityShortId(relayRealityShortId)
        .setRelayVlessTransport(normalizeRelayVlessTransport(relayVlessTransport, relayKind))
        .setRelayXhttpPath(relayXhttpPath)
        .setRelayXhttpHost(relayXhttpHost)
        .setRelayCloudflareTunnelMode(normalizeRelayCloudflareTunnelMode(relayCloudflareTunnelMode))
        .setRelayCloudflarePublishLocalOriginUrl(relayCloudflarePublishLocalOriginUrl)
        .setRelayCloudflareCredentialsRef(relayCloudflareCredentialsRef)
        .setRelayChainEntryServer(relayChainEntryServer)
        .setRelayChainEntryPort(relayChainEntryPort.takeIf { it > 0 } ?: DefaultHttpsPort)
        .setRelayChainEntryServerName(relayChainEntryServerName)
        .setRelayChainEntryPublicKey(relayChainEntryPublicKey)
        .setRelayChainEntryShortId(relayChainEntryShortId)
        .setRelayChainEntryProfileId(relayChainEntryProfileId)
        .setRelayChainExitServer(relayChainExitServer)
        .setRelayChainExitPort(relayChainExitPort.takeIf { it > 0 } ?: DefaultHttpsPort)
        .setRelayChainExitServerName(relayChainExitServerName)
        .setRelayChainExitPublicKey(relayChainExitPublicKey)
        .setRelayChainExitShortId(relayChainExitShortId)
        .setRelayChainExitProfileId(relayChainExitProfileId)
        .setRelayMasqueUrl(relayMasqueUrl)
        .setRelayMasqueUseHttp2Fallback(relayMasqueUseHttp2Fallback)
        .setRelayMasqueCloudflareGeohashEnabled(relayMasqueCloudflareGeohashEnabled)
        .setRelayTuicZeroRtt(relayTuicZeroRtt)
        .setRelayTuicCongestionControl(normalizeRelayCongestionControl(relayTuicCongestionControl))
        .setRelayShadowtlsInnerProfileId(relayShadowTlsInnerProfileId)
        .setRelayNaivePath(relayNaivePath)
        .setRelayLocalSocksHost(relayLocalSocksHost.ifBlank { DefaultRelayLocalSocksHost })
        .setRelayLocalSocksPort(relayLocalSocksPort.takeIf { it > 0 } ?: DefaultRelayLocalSocksPort)
        .setRelayUdpEnabled(relayUdpEnabled)
        .setRelayTcpFallbackEnabled(relayTcpFallbackEnabled)
        .setRelayFinalmaskType(normalizeRelayFinalmaskType(relayFinalmaskType))
        .setRelayFinalmaskHeaderHex(relayFinalmaskHeaderHex)
        .setRelayFinalmaskTrailerHex(relayFinalmaskTrailerHex)
        .setRelayFinalmaskRandRange(relayFinalmaskRandRange)
        .setRelayFinalmaskSudokuSeed(relayFinalmaskSudokuSeed)
        .setRelayFinalmaskFragmentPackets(relayFinalmaskFragmentPackets)
        .setRelayFinalmaskFragmentMinBytes(relayFinalmaskFragmentMinBytes)
        .setRelayFinalmaskFragmentMaxBytes(relayFinalmaskFragmentMaxBytes)
        .setDesyncAnyProtocol(desyncAnyProtocol)
        .setAppRoutingPolicyMode(normalizeAppRoutingPolicyMode(appRoutingPolicyMode))
        .clearAppRoutingEnabledPresetIds()
        .addAllAppRoutingEnabledPresetIds(
            appRoutingEnabledPresetIds
                .map(String::trim)
                .filter(String::isNotEmpty),
        ).setAntiCorrelationEnabled(antiCorrelationEnabled)
        .setDhtMitigationMode(normalizeDhtMitigationMode(dhtMitigationMode))
        .setGroupActivationFilterCompat(normalizeActivationFilter(groupActivationFilter))
        .also { builder ->
            tcpChainSteps.forEach { step ->
                builder.addTcpChainSteps(
                    com.poyka.ripdpi.proto.StrategyTcpStep
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
            udpChainSteps.forEach { step ->
                builder.addUdpChainSteps(
                    com.poyka.ripdpi.proto.StrategyUdpStep
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
        }.build()
}
