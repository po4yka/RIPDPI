package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val AppSettingsJsonFormatVersion = 1

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
)

@Serializable
internal data class AppSettingsUdpChainSnapshot(
    val kind: String,
    val count: Int,
    val splitBytes: Int = 0,
    val activationFilter: ActivationFilterModel = ActivationFilterModel(),
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
    val hostAutolearnEnabled: Boolean = defaultSettings.hostAutolearnEnabled,
    val hostAutolearnPenaltyTtlHours: Int = defaultSettings.hostAutolearnPenaltyTtlHours,
    val hostAutolearnMaxHosts: Int = defaultSettings.hostAutolearnMaxHosts,
    val networkStrategyMemoryEnabled: Boolean = defaultSettings.networkStrategyMemoryEnabled,
    val wsTunnelEnabled: Boolean = defaultSettings.wsTunnelEnabled,
    val wsTunnelMode: String = defaultSettings.wsTunnelMode,
    val groupActivationFilter: ActivationFilterModel = ActivationFilterModel(),
)

fun AppSettings.toJson(): String = appSettingsJson.encodeToString(toSnapshot())

fun appSettingsFromJson(payload: String): AppSettings =
    appSettingsJson.decodeFromString<AppSettingsSnapshot>(payload).toAppSettings()

private fun AppSettings.effectiveWsTunnelMode(): String =
    wsTunnelMode.ifEmpty { if (wsTunnelEnabled) "always" else "off" }

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
                    )
                },
            quicInitialMode = effectiveQuicInitialMode(),
            quicSupportV1 = effectiveQuicSupportV1(),
            quicSupportV2 = effectiveQuicSupportV2(),
            quicFakeProfile = effectiveQuicFakeProfile(),
            quicFakeHost = effectiveQuicFakeHost(),
            hostAutolearnEnabled = hostAutolearnEnabled,
            hostAutolearnPenaltyTtlHours = normalizeHostAutolearnPenaltyTtlHours(hostAutolearnPenaltyTtlHours),
            hostAutolearnMaxHosts = normalizeHostAutolearnMaxHosts(hostAutolearnMaxHosts),
            networkStrategyMemoryEnabled = networkStrategyMemoryEnabled,
            wsTunnelEnabled = wsTunnelEnabled,
            wsTunnelMode = effectiveWsTunnelMode(),
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
        .setHostAutolearnEnabled(hostAutolearnEnabled)
        .setHostAutolearnPenaltyTtlHours(normalizeHostAutolearnPenaltyTtlHours(hostAutolearnPenaltyTtlHours))
        .setHostAutolearnMaxHosts(normalizeHostAutolearnMaxHosts(hostAutolearnMaxHosts))
        .setNetworkStrategyMemoryEnabled(networkStrategyMemoryEnabled)
        .setWsTunnelEnabled(wsTunnelEnabled)
        .setWsTunnelMode(wsTunnelMode)
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
