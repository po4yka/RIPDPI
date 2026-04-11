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

@Serializable
internal data class AppSettingsTcpChainSnapshot(
    val kind: String,
    val marker: String,
    val midhostMarker: String? = null,
    val fakeHostTemplate: String? = null,
    val overlapSize: Int? = null,
    val fakeMode: String? = null,
    val fragmentCount: Int = 0,
    val minFragmentSize: Int = 0,
    val maxFragmentSize: Int = 0,
    val activationFilter: ActivationFilterModel = ActivationFilterModel(),
    val ipv6ExtensionProfile: String = StrategyIpv6ExtensionProfileNone,
)

@Serializable
internal data class AppSettingsUdpChainSnapshot(
    val kind: String,
    val count: Int,
    val splitBytes: Int = 0,
    val activationFilter: ActivationFilterModel = ActivationFilterModel(),
    val ipv6ExtensionProfile: String = StrategyIpv6ExtensionProfileNone,
)

@Serializable
internal data class AppSettingsSnapshot(
    val formatVersion: Int = AppSettingsJsonFormatVersion,
    val appTheme: String = defaultSettings.appTheme,
    val mode: Mode = Mode.fromString(defaultSettings.ripdpiMode),
    val dnsIp: String = defaultSettings.dnsIp,
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
    val ipv6Enabled: Boolean = defaultSettings.ipv6Enable,
    val enableCommandLineSettings: Boolean = defaultSettings.enableCmdSettings,
    val commandLineArgs: String = defaultSettings.cmdArgs,
    val proxyIp: String = defaultSettings.proxyIp,
    val proxyPort: Int = defaultSettings.proxyPort,
    val maxConnections: Int = defaultSettings.maxConnections,
    val bufferSize: Int = defaultSettings.bufferSize,
    val noDomain: Boolean = defaultSettings.noDomain,
    val tcpFastOpen: Boolean = defaultSettings.tcpFastOpen,
    val defaultTtl: Int = defaultSettings.defaultTtl,
    val customTtl: Boolean = defaultSettings.customTtl,
    val fakeTtl: Int = defaultSettings.fakeTtl,
    val adaptiveFakeTtlEnabled: Boolean = defaultSettings.adaptiveFakeTtlEnabled,
    val adaptiveFakeTtlDelta: Int = defaultSettings.adaptiveFakeTtlDelta,
    val adaptiveFakeTtlMin: Int = defaultSettings.adaptiveFakeTtlMin,
    val adaptiveFakeTtlMax: Int = defaultSettings.adaptiveFakeTtlMax,
    val adaptiveFakeTtlFallback: Int = defaultSettings.adaptiveFakeTtlFallback,
    val fakeSni: String = defaultSettings.fakeSni,
    val fakeOffset: Int = defaultSettings.fakeOffset,
    val fakeOffsetMarker: String = defaultSettings.fakeOffsetMarker,
    val fakeTlsSource: String = defaultSettings.fakeTlsSource,
    val fakeTlsSecondaryProfile: String = defaultSettings.fakeTlsSecondaryProfile,
    val fakeTcpTimestampEnabled: Boolean = defaultSettings.fakeTcpTimestampEnabled,
    val fakeTcpTimestampDeltaTicks: Int = defaultSettings.fakeTcpTimestampDeltaTicks,
    val fakeTlsUseOriginal: Boolean = defaultSettings.fakeTlsUseOriginal,
    val fakeTlsRandomize: Boolean = defaultSettings.fakeTlsRandomize,
    val fakeTlsDupSessionId: Boolean = defaultSettings.fakeTlsDupSessionId,
    val fakeTlsPadEncap: Boolean = defaultSettings.fakeTlsPadEncap,
    val fakeTlsSize: Int = defaultSettings.fakeTlsSize,
    val fakeTlsSniMode: String = defaultSettings.fakeTlsSniMode,
    val httpFakeProfile: String = defaultSettings.httpFakeProfile,
    val tlsFakeProfile: String = defaultSettings.tlsFakeProfile,
    val oobData: String = defaultSettings.oobData,
    val dropSack: Boolean = defaultSettings.dropSack,
    val desyncHttp: Boolean = defaultSettings.desyncHttp,
    val desyncHttps: Boolean = defaultSettings.desyncHttps,
    val desyncUdp: Boolean = defaultSettings.desyncUdp,
    val hostsMode: String = defaultSettings.hostsMode,
    val hostsBlacklist: String = defaultSettings.hostsBlacklist,
    val hostsWhitelist: String = defaultSettings.hostsWhitelist,
    val udpFakeProfile: String = defaultSettings.udpFakeProfile,
    val hostMixedCase: Boolean = defaultSettings.hostMixedCase,
    val domainMixedCase: Boolean = defaultSettings.domainMixedCase,
    val hostRemoveSpaces: Boolean = defaultSettings.hostRemoveSpaces,
    val httpMethodEol: Boolean = defaultSettings.httpMethodEol,
    val httpUnixEol: Boolean = defaultSettings.httpUnixEol,
    val httpMethodSpace: Boolean = defaultSettings.httpMethodSpace,
    val httpHostPad: Boolean = defaultSettings.httpHostPad,
    val onboardingComplete: Boolean = defaultSettings.onboardingComplete,
    val webrtcProtectionEnabled: Boolean = defaultSettings.webrtcProtectionEnabled,
    val biometricEnabled: Boolean = defaultSettings.biometricEnabled,
    @kotlinx.serialization.Transient
    val backupPin: String = "",
    val appIconVariant: String = defaultSettings.appIconVariant,
    val appIconStyle: String = defaultSettings.appIconStyle,
    val tcpChainSteps: List<AppSettingsTcpChainSnapshot> = emptyList(),
    val udpChainSteps: List<AppSettingsUdpChainSnapshot> = emptyList(),
    val quicInitialMode: String = defaultSettings.quicInitialMode,
    val quicSupportV1: Boolean = defaultSettings.quicSupportV1,
    val quicSupportV2: Boolean = defaultSettings.quicSupportV2,
    val quicFakeProfile: String = defaultSettings.quicFakeProfile,
    val quicFakeHost: String = defaultSettings.quicFakeHost,
    val quicBindLowPort: Boolean = defaultSettings.quicBindLowPort,
    val quicMigrateAfterHandshake: Boolean = defaultSettings.quicMigrateAfterHandshake,
    val hostAutolearnEnabled: Boolean = defaultSettings.hostAutolearnEnabled,
    val hostAutolearnPenaltyTtlHours: Int = defaultSettings.hostAutolearnPenaltyTtlHours,
    val hostAutolearnMaxHosts: Int = defaultSettings.hostAutolearnMaxHosts,
    val networkStrategyMemoryEnabled: Boolean = defaultSettings.networkStrategyMemoryEnabled,
    val strategyEvolution: Boolean = defaultSettings.strategyEvolution,
    val evolutionEpsilon: Double = defaultSettings.evolutionEpsilon,
    val entropyPaddingTargetPermil: Int = defaultSettings.entropyPaddingTargetPermil,
    val entropyPaddingMax: Int = defaultSettings.entropyPaddingMax,
    val entropyMode: String = entropyModeFromProto(defaultSettings.entropyMode),
    val shannonEntropyTargetPermil: Int = defaultSettings.shannonEntropyTargetPermil,
    val tlsFingerprintProfile: String = normalizeTlsFingerprintProfile(defaultSettings.tlsFingerprintProfile),
    val strategyPackChannel: String = defaultSettings.strategyPackChannel,
    val strategyPackPinnedId: String = defaultSettings.strategyPackPinnedId,
    val strategyPackPinnedVersion: String = defaultSettings.strategyPackPinnedVersion,
    val strategyPackRefreshPolicy: String = defaultSettings.strategyPackRefreshPolicy,
    val adaptiveFallbackEnabled: Boolean = defaultSettings.adaptiveFallbackEnabled,
    val adaptiveFallbackTorst: Boolean = defaultSettings.adaptiveFallbackTorst,
    val adaptiveFallbackTlsErr: Boolean = defaultSettings.adaptiveFallbackTlsErr,
    val adaptiveFallbackHttpRedirect: Boolean = defaultSettings.adaptiveFallbackHttpRedirect,
    val adaptiveFallbackConnectFailure: Boolean = defaultSettings.adaptiveFallbackConnectFailure,
    val adaptiveFallbackAutoSort: Boolean = defaultSettings.adaptiveFallbackAutoSort,
    val adaptiveFallbackCacheTtlSeconds: Int = defaultSettings.adaptiveFallbackCacheTtlSeconds,
    val adaptiveFallbackCachePrefixV4: Int = defaultSettings.adaptiveFallbackCachePrefixV4,
    val wsTunnelEnabled: Boolean = defaultSettings.wsTunnelEnabled,
    val wsTunnelMode: String = defaultSettings.wsTunnelMode,
    val warpEnabled: Boolean = defaultSettings.warpEnabled,
    val warpRouteMode: String = defaultSettings.warpRouteMode,
    val warpRouteHosts: String = defaultSettings.warpRouteHosts,
    val warpBuiltinRulesEnabled: Boolean = defaultSettings.warpBuiltinRulesEnabled,
    val warpProfileId: String = defaultSettings.warpProfileId,
    val warpAccountKind: String = defaultSettings.warpAccountKind,
    val warpZeroTrustOrg: String = defaultSettings.warpZeroTrustOrg,
    val warpSetupState: String = defaultSettings.warpSetupState,
    val warpLastScannerMode: String = defaultSettings.warpLastScannerMode,
    val warpEndpointSelectionMode: String = defaultSettings.warpEndpointSelectionMode,
    val warpManualEndpointHost: String = defaultSettings.warpManualEndpointHost,
    val warpManualEndpointV4: String = defaultSettings.warpManualEndpointV4,
    val warpManualEndpointV6: String = defaultSettings.warpManualEndpointV6,
    val warpManualEndpointPort: Int = defaultSettings.warpManualEndpointPort,
    val warpScannerEnabled: Boolean = defaultSettings.warpScannerEnabled,
    val warpScannerParallelism: Int = defaultSettings.warpScannerParallelism,
    val warpScannerMaxRttMs: Int = defaultSettings.warpScannerMaxRttMs,
    val warpAmneziaEnabled: Boolean = defaultSettings.warpAmneziaEnabled,
    val warpAmneziaJc: Int = defaultSettings.warpAmneziaJc,
    val warpAmneziaJmin: Int = defaultSettings.warpAmneziaJmin,
    val warpAmneziaJmax: Int = defaultSettings.warpAmneziaJmax,
    val warpAmneziaH1: Long = defaultSettings.warpAmneziaH1,
    val warpAmneziaH2: Long = defaultSettings.warpAmneziaH2,
    val warpAmneziaH3: Long = defaultSettings.warpAmneziaH3,
    val warpAmneziaH4: Long = defaultSettings.warpAmneziaH4,
    val warpAmneziaS1: Int = defaultSettings.warpAmneziaS1,
    val warpAmneziaS2: Int = defaultSettings.warpAmneziaS2,
    val warpAmneziaS3: Int = defaultSettings.warpAmneziaS3,
    val warpAmneziaS4: Int = defaultSettings.warpAmneziaS4,
    val warpAmneziaPreset: String = defaultSettings.warpAmneziaPreset,
    val relayEnabled: Boolean = defaultSettings.relayEnabled,
    val relayKind: String = defaultSettings.relayKind,
    val relayProfileId: String = defaultSettings.relayProfileId,
    val relayOutboundBindIp: String = defaultSettings.relayOutboundBindIp,
    val relayServer: String = defaultSettings.relayServer,
    val relayServerPort: Int = defaultSettings.relayServerPort,
    val relayServerName: String = defaultSettings.relayServerName,
    val relayRealityPublicKey: String = defaultSettings.relayRealityPublicKey,
    val relayRealityShortId: String = defaultSettings.relayRealityShortId,
    val relayVlessTransport: String = defaultSettings.relayVlessTransport,
    val relayXhttpPath: String = defaultSettings.relayXhttpPath,
    val relayXhttpHost: String = defaultSettings.relayXhttpHost,
    val relayChainEntryServer: String = defaultSettings.relayChainEntryServer,
    val relayChainEntryPort: Int = defaultSettings.relayChainEntryPort,
    val relayChainEntryServerName: String = defaultSettings.relayChainEntryServerName,
    val relayChainEntryPublicKey: String = defaultSettings.relayChainEntryPublicKey,
    val relayChainEntryShortId: String = defaultSettings.relayChainEntryShortId,
    val relayChainEntryProfileId: String = defaultSettings.relayChainEntryProfileId,
    val relayChainExitServer: String = defaultSettings.relayChainExitServer,
    val relayChainExitPort: Int = defaultSettings.relayChainExitPort,
    val relayChainExitServerName: String = defaultSettings.relayChainExitServerName,
    val relayChainExitPublicKey: String = defaultSettings.relayChainExitPublicKey,
    val relayChainExitShortId: String = defaultSettings.relayChainExitShortId,
    val relayChainExitProfileId: String = defaultSettings.relayChainExitProfileId,
    val relayMasqueUrl: String = defaultSettings.relayMasqueUrl,
    val relayMasqueUseHttp2Fallback: Boolean = defaultSettings.relayMasqueUseHttp2Fallback,
    val relayMasqueCloudflareGeohashEnabled: Boolean = defaultSettings.relayMasqueCloudflareGeohashEnabled,
    val relayTuicZeroRtt: Boolean = defaultSettings.relayTuicZeroRtt,
    val relayTuicCongestionControl: String = defaultSettings.relayTuicCongestionControl,
    val relayShadowTlsInnerProfileId: String = defaultSettings.relayShadowtlsInnerProfileId,
    val relayNaivePath: String = defaultSettings.relayNaivePath,
    val relayLocalSocksHost: String = defaultSettings.relayLocalSocksHost,
    val relayLocalSocksPort: Int = defaultSettings.relayLocalSocksPort,
    val relayUdpEnabled: Boolean = defaultSettings.relayUdpEnabled,
    val relayTcpFallbackEnabled: Boolean = defaultSettings.relayTcpFallbackEnabled,
    val desyncAnyProtocol: Boolean = defaultSettings.desyncAnyProtocol,
    val appRoutingPolicyMode: String = defaultSettings.appRoutingPolicyMode,
    val appRoutingEnabledPresetIds: List<String> = defaultSettings.appRoutingEnabledPresetIdsList,
    val antiCorrelationEnabled: Boolean = defaultSettings.antiCorrelationEnabled,
    val dhtMitigationMode: String = defaultSettings.dhtMitigationMode,
    val groupActivationFilter: ActivationFilterModel = ActivationFilterModel(),
)

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
