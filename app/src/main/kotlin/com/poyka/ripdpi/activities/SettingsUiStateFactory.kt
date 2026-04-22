package com.poyka.ripdpi.activities

import com.poyka.ripdpi.core.RipDpiPlatformCapabilities
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultEntropyPaddingMax
import com.poyka.ripdpi.data.DefaultEntropyPaddingTargetPermil
import com.poyka.ripdpi.data.DefaultEvolutionEpsilon
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.DefaultShannonEntropyTargetPermil
import com.poyka.ripdpi.data.DefaultTlsRandRecFragmentCount
import com.poyka.ripdpi.data.DefaultTlsRandRecMaxFragmentSize
import com.poyka.ripdpi.data.DefaultTlsRandRecMinFragmentSize
import com.poyka.ripdpi.data.DhtMitigationModeOff
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlDelta
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlMax
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlMin
import com.poyka.ripdpi.data.effectiveAppRoutingEnabledPresetIds
import com.poyka.ripdpi.data.effectiveFakeOffsetMarker
import com.poyka.ripdpi.data.effectiveFakeTlsSniMode
import com.poyka.ripdpi.data.effectiveGroupActivationFilter
import com.poyka.ripdpi.data.effectiveHttpFakeProfile
import com.poyka.ripdpi.data.effectiveIpIdMode
import com.poyka.ripdpi.data.effectiveQuicFakeHost
import com.poyka.ripdpi.data.effectiveQuicFakeProfile
import com.poyka.ripdpi.data.effectiveQuicInitialMode
import com.poyka.ripdpi.data.effectiveQuicSupportV1
import com.poyka.ripdpi.data.effectiveQuicSupportV2
import com.poyka.ripdpi.data.effectiveSplitMarker
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveTlsFakeProfile
import com.poyka.ripdpi.data.effectiveTlsRecordMarker
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.effectiveUdpFakeProfile
import com.poyka.ripdpi.data.entropyModeFromProto
import com.poyka.ripdpi.data.formatChainSummary
import com.poyka.ripdpi.data.formatStrategyChainDsl
import com.poyka.ripdpi.data.isTlsPrelude
import com.poyka.ripdpi.data.normalizeAppRoutingPolicyMode
import com.poyka.ripdpi.data.normalizeDhtMitigationMode
import com.poyka.ripdpi.data.normalizeHostAutolearnMaxHosts
import com.poyka.ripdpi.data.normalizeHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.normalizeTlsFingerprintProfile
import com.poyka.ripdpi.data.primaryDesyncMethod
import com.poyka.ripdpi.data.primaryTcpChainStep
import com.poyka.ripdpi.data.tlsPreludeTcpChainStep
import com.poyka.ripdpi.data.toAdaptiveFallbackSettingsModel
import com.poyka.ripdpi.data.toWarpSettingsModel
import com.poyka.ripdpi.data.usesSeqOverlapFakeProfile
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot

private data class ChainAnalysisResult(
    val tcpChainSteps: List<TcpChainStepModel>,
    val udpChainSteps: List<UdpChainStepModel>,
    val tlsPreludeSteps: List<TcpChainStepModel>,
    val primaryTcpStep: TcpChainStepModel?,
    val tlsRecStep: TcpChainStepModel?,
    val normalizedDesyncMethod: String,
    val desyncEnabled: Boolean,
    val isFake: Boolean,
    val usesFakeTransport: Boolean,
    val usesSeqOverlapFakeProfile: Boolean,
    val hasHostFake: Boolean,
    val hasDisoob: Boolean,
    val isOob: Boolean,
    val desyncHttpEnabled: Boolean,
    val desyncHttpsEnabled: Boolean,
    val desyncUdpEnabled: Boolean,
    val tlsRecEnabled: Boolean,
)

private fun AppSettings.analyzeChainFlags(): ChainAnalysisResult {
    val tcpChainSteps = effectiveTcpChainSteps()
    val udpChainSteps = effectiveUdpChainSteps()
    val tlsPreludeSteps = tcpChainSteps.filter { it.kind.isTlsPrelude }
    val primaryTcpStep = primaryTcpChainStep(tcpChainSteps)
    val tlsRecStep = tlsPreludeTcpChainStep(tcpChainSteps)
    val normalizedDesyncMethod = primaryDesyncMethod(tcpChainSteps).ifEmpty { "none" }
    val desyncEnabled = primaryTcpStep != null
    val isFake =
        tcpChainSteps.any {
            it.kind == TcpChainStepKind.Fake ||
                it.kind == TcpChainStepKind.FakeSplit ||
                it.kind == TcpChainStepKind.FakeDisorder
        }
    val usesFakeTransport =
        tcpChainSteps.any {
            it.kind == TcpChainStepKind.Fake ||
                it.kind == TcpChainStepKind.FakeSplit ||
                it.kind == TcpChainStepKind.FakeDisorder ||
                it.kind == TcpChainStepKind.HostFake
        }
    val desyncAllUnchecked = !desyncHttp && !desyncHttps && !desyncUdp
    val desyncHttpEnabled = desyncAllUnchecked || desyncHttp
    val desyncHttpsEnabled = desyncAllUnchecked || desyncHttps
    val desyncUdpEnabled = desyncAllUnchecked || desyncUdp
    return ChainAnalysisResult(
        tcpChainSteps = tcpChainSteps,
        udpChainSteps = udpChainSteps,
        tlsPreludeSteps = tlsPreludeSteps,
        primaryTcpStep = primaryTcpStep,
        tlsRecStep = tlsRecStep,
        normalizedDesyncMethod = normalizedDesyncMethod,
        desyncEnabled = desyncEnabled,
        isFake = isFake,
        usesFakeTransport = usesFakeTransport,
        usesSeqOverlapFakeProfile = tcpChainSteps.any { it.usesSeqOverlapFakeProfile() },
        hasHostFake = tcpChainSteps.any { it.kind == TcpChainStepKind.HostFake },
        hasDisoob = tcpChainSteps.any { it.kind == TcpChainStepKind.Disoob },
        isOob = tcpChainSteps.any { it.kind == TcpChainStepKind.Oob || it.kind == TcpChainStepKind.Disoob },
        desyncHttpEnabled = desyncHttpEnabled,
        desyncHttpsEnabled = desyncHttpsEnabled,
        desyncUdpEnabled = desyncUdpEnabled,
        tlsRecEnabled = desyncHttpsEnabled && tlsRecStep != null,
    )
}

private fun AppSettings.buildDnsUiState(activeDns: ActiveDnsSettings): DnsUiState =
    DnsUiState(
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
        dnsSummary = activeDns.summary(),
    )

private fun AppSettings.buildProxyUiState(): ProxyNetworkUiState =
    ProxyNetworkUiState(
        proxyIp = proxyIp.ifEmpty { "127.0.0.1" },
        proxyPort = proxyPort.takeIf { it > 0 } ?: 1080,
        maxConnections = maxConnections.takeIf { it > 0 } ?: 512,
        bufferSize = bufferSize.takeIf { it > 0 } ?: 16_384,
        noDomain = noDomain,
        tcpFastOpen = tcpFastOpen,
    )

private fun AppSettings.buildDesyncUiState(chain: ChainAnalysisResult): DesyncCoreUiState =
    DesyncCoreUiState(
        desyncMethod = chain.normalizedDesyncMethod,
        tcpChainSteps = chain.tcpChainSteps,
        udpChainSteps = chain.udpChainSteps,
        groupActivationFilter = effectiveGroupActivationFilter(),
        chainSummary = formatChainSummary(chain.tcpChainSteps, chain.udpChainSteps),
        chainDsl = formatStrategyChainDsl(chain.tcpChainSteps, chain.udpChainSteps),
        splitMarker = chain.primaryTcpStep?.marker ?: effectiveSplitMarker(),
        udpFakeCount = chain.udpChainSteps.sumOf { it.count.coerceAtLeast(0) },
        defaultTtl = defaultTtl,
        customTtl = customTtl,
    )

private fun AppSettings.buildFakeUiState(): FakeTransportUiState =
    FakeTransportUiState(
        fakeTtl = fakeTtl.takeIf { it > 0 } ?: 8,
        adaptiveFakeTtlEnabled = adaptiveFakeTtlEnabled,
        adaptiveFakeTtlDelta = effectiveAdaptiveFakeTtlDelta(),
        adaptiveFakeTtlMin = effectiveAdaptiveFakeTtlMin(),
        adaptiveFakeTtlMax = effectiveAdaptiveFakeTtlMax(),
        adaptiveFakeTtlFallback = effectiveAdaptiveFakeTtlFallback(),
        fakeSni = fakeSni.ifEmpty { DefaultFakeSni },
        fakeOffsetMarker = effectiveFakeOffsetMarker(),
        httpFakeProfile = effectiveHttpFakeProfile(),
        fakeTlsUseOriginal = fakeTlsUseOriginal,
        fakeTlsRandomize = fakeTlsRandomize,
        fakeTlsDupSessionId = fakeTlsDupSessionId,
        fakeTlsPadEncap = fakeTlsPadEncap,
        fakeTlsSize = fakeTlsSize,
        fakeTlsSniMode = effectiveFakeTlsSniMode(),
        tlsFakeProfile = effectiveTlsFakeProfile(),
        udpFakeProfile = effectiveUdpFakeProfile(),
        oobData = oobData.ifEmpty { "a" },
        dropSack = dropSack,
        ipIdMode = effectiveIpIdMode(),
    )

private fun AppSettings.buildTlsPreludeUiState(chain: ChainAnalysisResult): TlsPreludeUiState =
    TlsPreludeUiState(
        tlsrecEnabled = chain.tlsRecStep != null,
        tlsrecMarker = chain.tlsRecStep?.marker ?: effectiveTlsRecordMarker(),
        tlsPreludeMode = chain.tlsRecStep?.kind?.wireName ?: "disabled",
        tlsPreludeStepCount = chain.tlsPreludeSteps.size,
        tlsRandRecFragmentCount =
            chain.tlsRecStep?.fragmentCount?.takeIf {
                it > 0
            }
                ?: DefaultTlsRandRecFragmentCount,
        tlsRandRecMinFragmentSize =
            chain.tlsRecStep?.minFragmentSize?.takeIf { it > 0 }
                ?: DefaultTlsRandRecMinFragmentSize,
        tlsRandRecMaxFragmentSize =
            chain.tlsRecStep?.maxFragmentSize?.takeIf { it > 0 }
                ?: DefaultTlsRandRecMaxFragmentSize,
    )

private fun AppSettings.buildQuicUiState(): QuicUiState =
    QuicUiState(
        quicInitialMode = effectiveQuicInitialMode(),
        quicSupportV1 = effectiveQuicSupportV1(),
        quicSupportV2 = effectiveQuicSupportV2(),
        quicFakeProfile = effectiveQuicFakeProfile(),
        quicFakeHost = effectiveQuicFakeHost(),
    )

private fun AppSettings.buildDetectionResistanceUiState(): DetectionResistanceUiState =
    DetectionResistanceUiState(
        quicBindLowPort = quicBindLowPort,
        quicMigrateAfterHandshake = quicMigrateAfterHandshake,
        strategyEvolution = strategyEvolution,
        evolutionEpsilon = evolutionEpsilon.takeIf { it in 0.0..1.0 } ?: DefaultEvolutionEpsilon,
        tlsFingerprintProfile = normalizeTlsFingerprintProfile(tlsFingerprintProfile),
        entropyMode = entropyModeFromProto(entropyMode),
        entropyPaddingTargetPermil = entropyPaddingTargetPermil.takeIf { it > 0 } ?: DefaultEntropyPaddingTargetPermil,
        entropyPaddingMax = entropyPaddingMax.takeIf { it > 0 } ?: DefaultEntropyPaddingMax,
        shannonEntropyTargetPermil =
            shannonEntropyTargetPermil.takeIf { it > 0 } ?: DefaultShannonEntropyTargetPermil,
    )

private fun AppSettings.buildAutolearnUiState(
    proxyTelemetry: NativeRuntimeSnapshot,
    hostAutolearnStorePresent: Boolean,
    rememberedNetworkCount: Int,
): HostAutolearnUiState =
    HostAutolearnUiState(
        hostAutolearnEnabled = hostAutolearnEnabled,
        hostAutolearnPenaltyTtlHours = normalizeHostAutolearnPenaltyTtlHours(hostAutolearnPenaltyTtlHours),
        hostAutolearnMaxHosts = normalizeHostAutolearnMaxHosts(hostAutolearnMaxHosts),
        networkStrategyMemoryEnabled = networkStrategyMemoryEnabled,
        wsTunnelMode = wsTunnelMode.ifEmpty { if (wsTunnelEnabled) "always" else "off" },
        rememberedNetworkCount = rememberedNetworkCount,
        hostAutolearnRuntimeEnabled = proxyTelemetry.autolearnEnabled,
        hostAutolearnStorePresent = hostAutolearnStorePresent,
        hostAutolearnLearnedHostCount = proxyTelemetry.learnedHostCount,
        hostAutolearnPenalizedHostCount = proxyTelemetry.penalizedHostCount,
        hostAutolearnBlockedHostCount = proxyTelemetry.blockedHostCount,
        hostAutolearnLastBlockSignal = proxyTelemetry.lastBlockSignal,
        hostAutolearnLastBlockProvider = proxyTelemetry.lastBlockProvider,
        hostAutolearnLastHost = proxyTelemetry.lastAutolearnHost,
        hostAutolearnLastGroup = proxyTelemetry.lastAutolearnGroup,
        hostAutolearnLastAction = proxyTelemetry.lastAutolearnAction,
    )

private fun AppSettings.buildWarpUiState(
    suggestedAmneziaPresetId: String = "",
    suggestedAmneziaPresetLabel: String = "",
): WarpUiState {
    val warp = toWarpSettingsModel()
    return WarpUiState(
        enabled = warp.enabled,
        routeMode = warp.routeMode,
        routeHosts = warp.routeHosts,
        builtInRulesEnabled = warp.builtInRulesEnabled,
        profileId = warp.profile.profileId,
        accountKind = warp.profile.accountKind,
        zeroTrustOrg = warp.profile.zeroTrustOrg,
        setupState = warp.profile.setupState,
        lastScannerMode = warp.profile.lastScannerMode,
        endpointSelectionMode = warp.endpointSelectionMode,
        manualEndpointHost = warp.manualEndpoint.host,
        manualEndpointIpv4 = warp.manualEndpoint.ipv4,
        manualEndpointIpv6 = warp.manualEndpoint.ipv6,
        manualEndpointPort = warp.manualEndpoint.port,
        scannerAvailable = false,
        scannerEnabled = warp.scannerEnabled,
        scannerParallelism = warp.scannerParallelism,
        scannerMaxRttMs = warp.scannerMaxRttMs,
        amneziaPreset = warp.amneziaPreset,
        amneziaEnabled = warp.amnezia.enabled,
        amneziaJc = warp.amnezia.jc,
        amneziaJmin = warp.amnezia.jmin,
        amneziaJmax = warp.amnezia.jmax,
        amneziaH1 = warp.amnezia.h1,
        amneziaH2 = warp.amnezia.h2,
        amneziaH3 = warp.amnezia.h3,
        amneziaH4 = warp.amnezia.h4,
        amneziaS1 = warp.amnezia.s1,
        amneziaS2 = warp.amnezia.s2,
        amneziaS3 = warp.amnezia.s3,
        amneziaS4 = warp.amnezia.s4,
        amneziaSuggestedPresetId = suggestedAmneziaPresetId,
        amneziaSuggestedPresetLabel = suggestedAmneziaPresetLabel,
    )
}

private fun AppSettings.buildHttpParserUiState(): HttpParserUiState =
    HttpParserUiState(
        hostMixedCase = hostMixedCase,
        domainMixedCase = domainMixedCase,
        hostRemoveSpaces = hostRemoveSpaces,
        httpHostPad = httpHostPad,
        httpMethodEol = httpMethodEol,
        httpUnixEol = httpUnixEol,
        httpMethodSpace = httpMethodSpace,
        httpHostExtraSpace = httpHostExtraSpace,
        httpHostTab = httpHostTab,
    )

private fun AppSettings.buildAdaptiveFallbackUiState(
    proxyTelemetry: NativeRuntimeSnapshot,
    runtimeOverrideRememberedPolicy: Boolean,
): AdaptiveFallbackUiState {
    val adaptive = toAdaptiveFallbackSettingsModel()
    return AdaptiveFallbackUiState(
        enabled = adaptive.enabled,
        torst = adaptive.torst,
        tlsErr = adaptive.tlsErr,
        httpRedirect = adaptive.httpRedirect,
        connectFailure = adaptive.connectFailure,
        autoSort = adaptive.autoSort,
        cacheTtlSeconds = adaptive.cacheTtlSeconds,
        cachePrefixV4 = adaptive.cachePrefixV4,
        runtimeOverrideActive = proxyTelemetry.adaptiveOverrideActive,
        runtimeOverrideRememberedPolicy = proxyTelemetry.adaptiveOverrideActive && runtimeOverrideRememberedPolicy,
        runtimeRouteGroup = proxyTelemetry.lastRouteGroup,
        runtimeTriggerMask = proxyTelemetry.adaptiveTriggerMask,
        runtimeLastTrigger = proxyTelemetry.adaptiveLastTrigger,
        runtimeOverrideReason = proxyTelemetry.adaptiveOverrideReason,
    )
}

private fun AppSettings.buildRoutingProtectionUiState(
    snapshot: RoutingProtectionCatalogSnapshot,
    serviceTelemetry: ServiceTelemetrySnapshot,
): RoutingProtectionUiState {
    val enabledPresetIds = effectiveAppRoutingEnabledPresetIds().toSet()
    val presets =
        snapshot.presets.map { preset ->
            val confirmedCount = snapshot.detectedApps.count { it.presetId == preset.id && it.vpnDetection }
            RoutingProtectionPresetUiState(
                id = preset.id,
                title = preset.title,
                enabled = preset.id in enabledPresetIds,
                matchedPackages = preset.matchedPackages,
                detectionMethod = preset.detectionMethod,
                fixCoverage = preset.fixCoverage,
                limitations = preset.limitations,
                confirmedDetectorCount = confirmedCount,
            )
        }
    val suggestions = buildRoutingProtectionSuggestions(presets, snapshot, serviceTelemetry)
    return RoutingProtectionUiState(
        policyMode = normalizeAppRoutingPolicyMode(appRoutingPolicyMode),
        enabledPresetIds = enabledPresetIds.toList().sorted(),
        antiCorrelationEnabled = antiCorrelationEnabled,
        dhtMitigationMode = normalizeDhtMitigationMode(dhtMitigationMode),
        fullTunnelMode = fullTunnelMode,
        presets = presets,
        detectedApps =
            snapshot.detectedApps.map { app ->
                RoutingProtectionDetectedAppUiState(
                    packageName = app.packageName,
                    presetTitle = app.presetTitle,
                    detectionMethod = app.detectionMethod,
                    fixCoverage = app.fixCoverage,
                    vpnDetection = app.vpnDetection,
                    severity = app.severity,
                )
            },
        suggestions = suggestions,
    )
}

private const val FullTunnelSuggestionDetectedAppsThreshold = 3

private fun AppSettings.buildRoutingProtectionSuggestions(
    presets: List<RoutingProtectionPresetUiState>,
    snapshot: RoutingProtectionCatalogSnapshot,
    serviceTelemetry: ServiceTelemetrySnapshot,
): List<RoutingProtectionSuggestionUiState> =
    buildList {
        if (!fullTunnelMode && presets.any { it.matchedPackages.isNotEmpty() && !it.enabled }) {
            add(
                RoutingProtectionSuggestionUiState(
                    id = "exact_app_routing",
                    title = "Suggest app routing presets",
                    body =
                        "Whitelist-sensitive apps are installed. Enable direct routing for the matched presets " +
                            "instead of assuming split tunneling stays hidden.",
                ),
            )
        }
        if (!fullTunnelMode && snapshot.detectedApps.size >= FullTunnelSuggestionDetectedAppsThreshold) {
            add(
                RoutingProtectionSuggestionUiState(
                    id = "full_tunnel",
                    title = "Suggest full tunnel mode",
                    body =
                        "Several risky apps are installed. Full tunnel mode removes per-app routing differences " +
                            "when exact exclusions are not enough.",
                ),
            )
        }
        if (!fullTunnelMode && !antiCorrelationEnabled && snapshot.detectedApps.isNotEmpty()) {
            add(
                RoutingProtectionSuggestionUiState(
                    id = "anti_correlation",
                    title = "Suggest anti-correlation mode",
                    body =
                        "Anti-correlation keeps domestic destinations direct while forcing CDN-heavy paths " +
                            "through VPN or relay. It is meant for mobile whitelist pressure, not generic " +
                            "split tunneling.",
                ),
            )
        }
        if (shouldSuggestDhtMitigation(presets, serviceTelemetry)) {
            add(
                RoutingProtectionSuggestionUiState(
                    id = "dht_mitigation",
                    title = "Suggest DHT trigger mitigation",
                    body = dhtMitigationSuggestionBody(serviceTelemetry),
                ),
            )
        }
    }

private fun AppSettings.shouldSuggestDhtMitigation(
    presets: List<RoutingProtectionPresetUiState>,
    serviceTelemetry: ServiceTelemetrySnapshot,
): Boolean =
    !fullTunnelMode &&
        normalizeDhtMitigationMode(dhtMitigationMode) == DhtMitigationModeOff &&
        (presets.any { it.enabled } || antiCorrelationEnabled || serviceTelemetry.hasRecentRoutingPressure())

private fun dhtMitigationSuggestionBody(serviceTelemetry: ServiceTelemetrySnapshot): String {
    val dhtCorrelationReason = serviceTelemetry.runtimeFieldTelemetry.dhtTriggerCorrelationReason
    return when {
        dhtCorrelationReason != null -> {
            "$dhtCorrelationReason Enable DHT trigger mitigation so known trigger CIDRs do not keep " +
                "destabilizing relay or WARP paths."
        }

        serviceTelemetry.hasRecentRoutingPressure() -> {
            "Recent runtime failures suggest control-plane instability while split routing is active. " +
                "Known DHT trigger CIDRs can destabilize WARP, relay, or control-plane paths on mobile networks."
        }

        else -> {
            "Split-routing protection is active, but DHT mitigation is still off. Known trigger CIDRs can " +
                "destabilize WARP, relay, or control-plane paths on mobile networks."
        }
    }
}

internal fun AppSettings.toUiState(
    isHydrated: Boolean = true,
    serviceStatus: AppStatus = AppStatus.Halted,
    proxyTelemetry: NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "proxy"),
    serviceTelemetry: ServiceTelemetrySnapshot = ServiceTelemetrySnapshot(),
    hostAutolearnStorePresent: Boolean = false,
    rememberedNetworkCount: Int = 0,
    runtimeOverrideRememberedPolicy: Boolean = false,
    biometricAvailability: Int = androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS,
    routingProtectionSnapshot: RoutingProtectionCatalogSnapshot = RoutingProtectionCatalogSnapshot(),
    suggestedWarpAmneziaPresetId: String = "",
    suggestedWarpAmneziaPresetLabel: String = "",
): SettingsUiState {
    val normalizedMode = ripdpiMode.ifEmpty { "vpn" }
    val activeDns = activeDnsSettings()
    val chain = analyzeChainFlags()
    val isVpn = normalizedMode == "vpn"

    return SettingsUiState(
        settings = this,
        appTheme = appTheme.ifEmpty { "system" },
        appIconVariant = LauncherIconManager.normalizeIconKey(appIconVariant),
        themedAppIconEnabled =
            LauncherIconManager.normalizeIconStyle(appIconStyle) == LauncherIconManager.ThemedIconStyle,
        ripdpiMode = normalizedMode,
        dns = buildDnsUiState(activeDns),
        ipv6Enable = ipv6Enable,
        enableCmdSettings = enableCmdSettings,
        cmdArgs = cmdArgs,
        proxy = buildProxyUiState(),
        desync = buildDesyncUiState(chain),
        fake = buildFakeUiState(),
        desyncHttp = desyncHttp,
        desyncHttps = desyncHttps,
        desyncUdp = desyncUdp,
        hostsMode = hostsMode.ifEmpty { "disable" },
        hostsBlacklist = hostsBlacklist,
        hostsWhitelist = hostsWhitelist,
        tlsPrelude = buildTlsPreludeUiState(chain),
        quic = buildQuicUiState(),
        detectionResistance = buildDetectionResistanceUiState(),
        warp = buildWarpUiState(suggestedWarpAmneziaPresetId, suggestedWarpAmneziaPresetLabel),
        routingProtection = buildRoutingProtectionUiState(routingProtectionSnapshot, serviceTelemetry),
        autolearn = buildAutolearnUiState(proxyTelemetry, hostAutolearnStorePresent, rememberedNetworkCount),
        adaptiveFallback = buildAdaptiveFallbackUiState(proxyTelemetry, runtimeOverrideRememberedPolicy),
        httpParser = buildHttpParserUiState(),
        onboardingComplete = onboardingComplete,
        webrtcProtectionEnabled = webrtcProtectionEnabled,
        excludeRussianAppsEnabled =
            effectiveAppRoutingEnabledPresetIds().contains(com.poyka.ripdpi.data.DefaultAppRoutingRussianPresetId),
        fullTunnelMode = fullTunnelMode,
        biometricEnabled = biometricEnabled,
        biometricAvailability = biometricAvailability,
        backupPinHash = backupPin,
        diagnosticsMonitorEnabled = diagnosticsMonitorEnabled,
        diagnosticsSampleIntervalSeconds =
            diagnosticsSampleIntervalSeconds
                .takeIf { it > 0 }
                ?: AppSettingsSerializer.defaultValue.diagnosticsSampleIntervalSeconds,
        diagnosticsHistoryRetentionDays =
            diagnosticsHistoryRetentionDays
                .takeIf { it > 0 }
                ?: AppSettingsSerializer.defaultValue.diagnosticsHistoryRetentionDays,
        diagnosticsExportIncludeHistory = diagnosticsExportIncludeHistory,
        strategyPackAllowRollbackOverride = strategyPackAllowRollbackOverride,
        serviceStatus = serviceStatus,
        isVpn = isVpn,
        selectedMode = if (isVpn) Mode.VPN else Mode.Proxy,
        useCmdSettings = enableCmdSettings,
        desyncEnabled = chain.desyncEnabled,
        isFake = chain.isFake,
        usesFakeTransport = chain.usesFakeTransport,
        seqovlSupported = RipDpiPlatformCapabilities.seqovlSupported(),
        usesSeqOverlapFakeProfile = chain.usesSeqOverlapFakeProfile,
        hasHostFake = chain.hasHostFake,
        hasDisoob = chain.hasDisoob,
        isOob = chain.isOob,
        desyncHttpEnabled = chain.desyncHttpEnabled,
        desyncHttpsEnabled = chain.desyncHttpsEnabled,
        desyncUdpEnabled = chain.desyncUdpEnabled,
        tlsRecEnabled = chain.tlsRecEnabled,
        isHydrated = isHydrated,
    )
}

private fun ServiceTelemetrySnapshot.hasRecentRoutingPressure(): Boolean =
    runtimeFieldTelemetry.dhtTriggerCorrelationActive ||
        listOf(proxyTelemetry, relayTelemetry, warpTelemetry).any { telemetry ->
            telemetry.lastFailureClass?.isNotBlank() == true || telemetry.lastError?.isNotBlank() == true
        }
