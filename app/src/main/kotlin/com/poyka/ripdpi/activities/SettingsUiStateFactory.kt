package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.DefaultTlsRandRecFragmentCount
import com.poyka.ripdpi.data.DefaultTlsRandRecMaxFragmentSize
import com.poyka.ripdpi.data.DefaultTlsRandRecMinFragmentSize
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlDelta
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlMax
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlMin
import com.poyka.ripdpi.data.effectiveFakeOffsetMarker
import com.poyka.ripdpi.data.effectiveFakeTlsSniMode
import com.poyka.ripdpi.data.effectiveGroupActivationFilter
import com.poyka.ripdpi.data.effectiveHttpFakeProfile
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
import com.poyka.ripdpi.data.formatChainSummary
import com.poyka.ripdpi.data.formatStrategyChainDsl
import com.poyka.ripdpi.data.isTlsPrelude
import com.poyka.ripdpi.data.normalizeHostAutolearnMaxHosts
import com.poyka.ripdpi.data.normalizeHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.primaryDesyncMethod
import com.poyka.ripdpi.data.primaryTcpChainStep
import com.poyka.ripdpi.data.tlsPreludeTcpChainStep
import com.poyka.ripdpi.proto.AppSettings

internal fun AppSettings.toUiState(
    isHydrated: Boolean = true,
    serviceStatus: AppStatus = AppStatus.Halted,
    proxyTelemetry: NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "proxy"),
    hostAutolearnStorePresent: Boolean = false,
    rememberedNetworkCount: Int = 0,
): SettingsUiState {
    val normalizedMode = ripdpiMode.ifEmpty { "vpn" }
    val activeDns = activeDnsSettings()
    val tcpChainSteps = effectiveTcpChainSteps()
    val udpChainSteps = effectiveUdpChainSteps()
    val tlsPreludeSteps = tcpChainSteps.filter { it.kind.isTlsPrelude }
    val primaryTcpStep = primaryTcpChainStep(tcpChainSteps)
    val tlsRecStep = tlsPreludeTcpChainStep(tcpChainSteps)
    val normalizedDesyncMethod = primaryDesyncMethod(tcpChainSteps).ifEmpty { "none" }
    val normalizedHostsMode = hostsMode.ifEmpty { "disable" }
    val isVpn = normalizedMode == "vpn"
    val useCmdSettings = enableCmdSettings
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
    val hasHostFake = tcpChainSteps.any { it.kind == TcpChainStepKind.HostFake }
    val hasDisoob = tcpChainSteps.any { it.kind == TcpChainStepKind.Disoob }
    val isOob = tcpChainSteps.any { it.kind == TcpChainStepKind.Oob || it.kind == TcpChainStepKind.Disoob }

    val desyncAllUnchecked = !desyncHttp && !desyncHttps && !desyncUdp
    val desyncHttpEnabled = desyncAllUnchecked || desyncHttp
    val desyncHttpsEnabled = desyncAllUnchecked || desyncHttps
    val desyncUdpEnabled = desyncAllUnchecked || desyncUdp
    val tlsRecEnabled = desyncHttpsEnabled && tlsRecStep != null

    return SettingsUiState(
        settings = this,
        appTheme = appTheme.ifEmpty { "system" },
        appIconVariant = LauncherIconManager.normalizeIconKey(appIconVariant),
        themedAppIconEnabled =
            LauncherIconManager.normalizeIconStyle(appIconStyle) == LauncherIconManager.ThemedIconStyle,
        ripdpiMode = normalizedMode,
        dns =
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
            ),
        ipv6Enable = ipv6Enable,
        enableCmdSettings = enableCmdSettings,
        cmdArgs = cmdArgs,
        proxy =
            ProxyNetworkUiState(
                proxyIp = proxyIp.ifEmpty { "127.0.0.1" },
                proxyPort = proxyPort.takeIf { it > 0 } ?: 1080,
                maxConnections = maxConnections.takeIf { it > 0 } ?: 512,
                bufferSize = bufferSize.takeIf { it > 0 } ?: 16_384,
                noDomain = noDomain,
                tcpFastOpen = tcpFastOpen,
            ),
        desync =
            DesyncCoreUiState(
                desyncMethod = normalizedDesyncMethod,
                tcpChainSteps = tcpChainSteps,
                udpChainSteps = udpChainSteps,
                groupActivationFilter = effectiveGroupActivationFilter(),
                chainSummary = formatChainSummary(tcpChainSteps, udpChainSteps),
                chainDsl = formatStrategyChainDsl(tcpChainSteps, udpChainSteps),
                splitMarker = primaryTcpStep?.marker ?: effectiveSplitMarker(),
                udpFakeCount = udpChainSteps.sumOf { it.count.coerceAtLeast(0) },
                defaultTtl = defaultTtl,
                customTtl = customTtl,
            ),
        fake =
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
            ),
        desyncHttp = desyncHttp,
        desyncHttps = desyncHttps,
        desyncUdp = desyncUdp,
        hostsMode = normalizedHostsMode,
        hostsBlacklist = hostsBlacklist,
        hostsWhitelist = hostsWhitelist,
        tlsPrelude =
            TlsPreludeUiState(
                tlsrecEnabled = tlsRecStep != null,
                tlsrecMarker = tlsRecStep?.marker ?: effectiveTlsRecordMarker(),
                tlsPreludeMode = tlsRecStep?.kind?.wireName ?: "disabled",
                tlsPreludeStepCount = tlsPreludeSteps.size,
                tlsRandRecFragmentCount =
                    tlsRecStep?.fragmentCount?.takeIf {
                        it > 0
                    }
                        ?: DefaultTlsRandRecFragmentCount,
                tlsRandRecMinFragmentSize =
                    tlsRecStep?.minFragmentSize?.takeIf { it > 0 }
                        ?: DefaultTlsRandRecMinFragmentSize,
                tlsRandRecMaxFragmentSize =
                    tlsRecStep?.maxFragmentSize?.takeIf { it > 0 }
                        ?: DefaultTlsRandRecMaxFragmentSize,
            ),
        quic =
            QuicUiState(
                quicInitialMode = effectiveQuicInitialMode(),
                quicSupportV1 = effectiveQuicSupportV1(),
                quicSupportV2 = effectiveQuicSupportV2(),
                quicFakeProfile = effectiveQuicFakeProfile(),
                quicFakeHost = effectiveQuicFakeHost(),
            ),
        autolearn =
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
                hostAutolearnLastHost = proxyTelemetry.lastAutolearnHost,
                hostAutolearnLastGroup = proxyTelemetry.lastAutolearnGroup,
                hostAutolearnLastAction = proxyTelemetry.lastAutolearnAction,
            ),
        httpParser =
            HttpParserUiState(
                hostMixedCase = hostMixedCase,
                domainMixedCase = domainMixedCase,
                hostRemoveSpaces = hostRemoveSpaces,
                httpMethodEol = httpMethodEol,
                httpUnixEol = httpUnixEol,
            ),
        onboardingComplete = onboardingComplete,
        webrtcProtectionEnabled = webrtcProtectionEnabled,
        biometricEnabled = biometricEnabled,
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
        serviceStatus = serviceStatus,
        isVpn = isVpn,
        selectedMode = if (isVpn) Mode.VPN else Mode.Proxy,
        useCmdSettings = useCmdSettings,
        desyncEnabled = desyncEnabled,
        isFake = isFake,
        usesFakeTransport = usesFakeTransport,
        hasHostFake = hasHostFake,
        hasDisoob = hasDisoob,
        isOob = isOob,
        desyncHttpEnabled = desyncHttpEnabled,
        desyncHttpsEnabled = desyncHttpsEnabled,
        desyncUdpEnabled = desyncUdpEnabled,
        tlsRecEnabled = tlsRecEnabled,
        isHydrated = isHydrated,
    )
}
