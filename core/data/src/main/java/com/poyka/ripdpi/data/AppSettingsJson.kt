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
)

@Serializable
internal data class AppSettingsUdpChainSnapshot(
    val kind: String,
    val count: Int,
)

@Serializable
internal data class AppSettingsSnapshot(
    val formatVersion: Int = AppSettingsJsonFormatVersion,
    val appTheme: String = defaultSettings.appTheme,
    val mode: Mode = Mode.fromString(defaultSettings.ripdpiMode),
    val dnsIp: String = defaultSettings.dnsIp,
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
    val desyncMethod: String = defaultSettings.desyncMethod,
    val splitPosition: Int = defaultSettings.splitPosition,
    val splitAtHost: Boolean = defaultSettings.splitAtHost,
    val splitMarker: String = defaultSettings.splitMarker,
    val fakeTtl: Int = defaultSettings.fakeTtl,
    val fakeSni: String = defaultSettings.fakeSni,
    val fakeOffset: Int = defaultSettings.fakeOffset,
    val fakeOffsetMarker: String = defaultSettings.fakeOffsetMarker,
    val oobData: String = defaultSettings.oobData,
    val dropSack: Boolean = defaultSettings.dropSack,
    val desyncHttp: Boolean = defaultSettings.desyncHttp,
    val desyncHttps: Boolean = defaultSettings.desyncHttps,
    val desyncUdp: Boolean = defaultSettings.desyncUdp,
    val hostsMode: String = defaultSettings.hostsMode,
    val hostsBlacklist: String = defaultSettings.hostsBlacklist,
    val hostsWhitelist: String = defaultSettings.hostsWhitelist,
    val tlsrecEnabled: Boolean = defaultSettings.tlsrecEnabled,
    val tlsrecPosition: Int = defaultSettings.tlsrecPosition,
    val tlsrecAtSni: Boolean = defaultSettings.tlsrecAtSni,
    val tlsrecMarker: String = defaultSettings.tlsrecMarker,
    val udpFakeCount: Int = defaultSettings.udpFakeCount,
    val hostMixedCase: Boolean = defaultSettings.hostMixedCase,
    val domainMixedCase: Boolean = defaultSettings.domainMixedCase,
    val hostRemoveSpaces: Boolean = defaultSettings.hostRemoveSpaces,
    val onboardingComplete: Boolean = defaultSettings.onboardingComplete,
    val webrtcProtectionEnabled: Boolean = defaultSettings.webrtcProtectionEnabled,
    val biometricEnabled: Boolean = defaultSettings.biometricEnabled,
    val backupPin: String = defaultSettings.backupPin,
    val appIconVariant: String = defaultSettings.appIconVariant,
    val appIconStyle: String = defaultSettings.appIconStyle,
    val tcpChainSteps: List<AppSettingsTcpChainSnapshot> = emptyList(),
    val udpChainSteps: List<AppSettingsUdpChainSnapshot> = emptyList(),
)

fun AppSettings.toJson(): String = appSettingsJson.encodeToString(toSnapshot())

fun appSettingsFromJson(payload: String): AppSettings = appSettingsJson.decodeFromString<AppSettingsSnapshot>(payload).toAppSettings()

private fun AppSettings.toSnapshot(): AppSettingsSnapshot =
    AppSettingsSnapshot(
        appTheme = appTheme,
        mode = Mode.fromString(ripdpiMode.ifEmpty { defaultSettings.ripdpiMode }),
        dnsIp = dnsIp,
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
        desyncMethod = desyncMethod,
        splitPosition = splitPosition,
        splitAtHost = splitAtHost,
        splitMarker = splitMarker,
        fakeTtl = fakeTtl,
        fakeSni = fakeSni,
        fakeOffset = fakeOffset,
        fakeOffsetMarker = fakeOffsetMarker,
        oobData = oobData,
        dropSack = dropSack,
        desyncHttp = desyncHttp,
        desyncHttps = desyncHttps,
        desyncUdp = desyncUdp,
        hostsMode = hostsMode,
        hostsBlacklist = hostsBlacklist,
        hostsWhitelist = hostsWhitelist,
        tlsrecEnabled = tlsrecEnabled,
        tlsrecPosition = tlsrecPosition,
        tlsrecAtSni = tlsrecAtSni,
        tlsrecMarker = tlsrecMarker,
        udpFakeCount = udpFakeCount,
        hostMixedCase = hostMixedCase,
        domainMixedCase = domainMixedCase,
        hostRemoveSpaces = hostRemoveSpaces,
        onboardingComplete = onboardingComplete,
        webrtcProtectionEnabled = webrtcProtectionEnabled,
        biometricEnabled = biometricEnabled,
        backupPin = backupPin,
        appIconVariant = appIconVariant,
        appIconStyle = appIconStyle,
        tcpChainSteps = tcpChainStepsList.map { AppSettingsTcpChainSnapshot(kind = it.kind, marker = it.marker) },
        udpChainSteps = udpChainStepsList.map { AppSettingsUdpChainSnapshot(kind = it.kind, count = it.count) },
    )

private fun AppSettingsSnapshot.toAppSettings(): AppSettings {
    require(formatVersion == AppSettingsJsonFormatVersion) {
        "Unsupported app settings format version: $formatVersion"
    }

    return AppSettings
        .newBuilder()
        .setAppTheme(appTheme)
        .setRipdpiMode(mode.preferenceValue)
        .setDnsIp(dnsIp)
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
        .setDesyncMethod(desyncMethod)
        .setSplitPosition(splitPosition)
        .setSplitAtHost(splitAtHost)
        .setSplitMarker(splitMarker)
        .setFakeTtl(fakeTtl)
        .setFakeSni(fakeSni)
        .setFakeOffset(fakeOffset)
        .setFakeOffsetMarker(fakeOffsetMarker)
        .setOobData(oobData)
        .setDropSack(dropSack)
        .setDesyncHttp(desyncHttp)
        .setDesyncHttps(desyncHttps)
        .setDesyncUdp(desyncUdp)
        .setHostsMode(hostsMode)
        .setHostsBlacklist(hostsBlacklist)
        .setHostsWhitelist(hostsWhitelist)
        .setTlsrecEnabled(tlsrecEnabled)
        .setTlsrecPosition(tlsrecPosition)
        .setTlsrecAtSni(tlsrecAtSni)
        .setTlsrecMarker(tlsrecMarker)
        .setUdpFakeCount(udpFakeCount)
        .setHostMixedCase(hostMixedCase)
        .setDomainMixedCase(domainMixedCase)
        .setHostRemoveSpaces(hostRemoveSpaces)
        .setOnboardingComplete(onboardingComplete)
        .setWebrtcProtectionEnabled(webrtcProtectionEnabled)
        .setBiometricEnabled(biometricEnabled)
        .setBackupPin(backupPin)
        .setAppIconVariant(appIconVariant)
        .setAppIconStyle(appIconStyle)
        .also { builder ->
            tcpChainSteps.forEach { step ->
                builder.addTcpChainSteps(
                    com.poyka.ripdpi.proto.StrategyTcpStep
                        .newBuilder()
                        .setKind(step.kind)
                        .setMarker(step.marker)
                        .build(),
                )
            }
            udpChainSteps.forEach { step ->
                builder.addUdpChainSteps(
                    com.poyka.ripdpi.proto.StrategyUdpStep
                        .newBuilder()
                        .setKind(step.kind)
                        .setCount(step.count)
                        .build(),
                )
            }
        }
        .build()
}
