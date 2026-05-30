package com.poyka.ripdpi.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

internal data class AppSettingsProxyDesyncSnapshot(
    val enableCommandLineSettings: Boolean = defaultSettings.enableCmdSettings,
    val commandLineArgs: String = defaultSettings.cmdArgs,
    val proxyIp: String = defaultSettings.proxyIp,
    val proxyPort: Int = defaultSettings.proxyPort,
    val maxConnections: Int = defaultSettings.maxConnections,
    val bufferSize: Int = defaultSettings.bufferSize,
    val noDomain: Boolean = defaultSettings.noDomain,
    val tcpFastOpen: Boolean = defaultSettings.tcpFastOpen,
    val mixedInboundEnabled: Boolean = defaultSettings.mixedInboundEnabled,
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
    val ipIdMode: String = normalizeIpIdMode(defaultSettings.ipIdMode),
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
    val desyncAnyProtocol: Boolean = defaultSettings.desyncAnyProtocol,
    val groupActivationFilter: ActivationFilterModel = ActivationFilterModel(),
    val proxyAllowLan: Boolean = defaultSettings.proxyAllowLan,
    // Credential: present in the snapshot for round-trip, but never written to backup
    // (same pattern as backupPin in AppSettingsRootUiRuntimeSnapshot).
    val proxyLanAuthToken: String = "",
    val appendHttpProxy: Boolean = defaultSettings.appendHttpProxy,
)

internal fun JsonObjectBuilder.writeProxyDesyncSnapshot(snapshot: AppSettingsProxyDesyncSnapshot) {
    put("enableCommandLineSettings", snapshot.enableCommandLineSettings)
    put("commandLineArgs", snapshot.commandLineArgs)
    put("proxyIp", snapshot.proxyIp)
    put("proxyPort", snapshot.proxyPort)
    put("maxConnections", snapshot.maxConnections)
    put("bufferSize", snapshot.bufferSize)
    put("noDomain", snapshot.noDomain)
    put("tcpFastOpen", snapshot.tcpFastOpen)
    put("mixedInboundEnabled", snapshot.mixedInboundEnabled)
    put("defaultTtl", snapshot.defaultTtl)
    put("customTtl", snapshot.customTtl)
    put("fakeTtl", snapshot.fakeTtl)
    put("adaptiveFakeTtlEnabled", snapshot.adaptiveFakeTtlEnabled)
    put("adaptiveFakeTtlDelta", snapshot.adaptiveFakeTtlDelta)
    put("adaptiveFakeTtlMin", snapshot.adaptiveFakeTtlMin)
    put("adaptiveFakeTtlMax", snapshot.adaptiveFakeTtlMax)
    put("adaptiveFakeTtlFallback", snapshot.adaptiveFakeTtlFallback)
    put("fakeSni", snapshot.fakeSni)
    put("fakeOffset", snapshot.fakeOffset)
    put("fakeOffsetMarker", snapshot.fakeOffsetMarker)
    put("fakeTlsSource", snapshot.fakeTlsSource)
    put("fakeTlsSecondaryProfile", snapshot.fakeTlsSecondaryProfile)
    put("fakeTcpTimestampEnabled", snapshot.fakeTcpTimestampEnabled)
    put("fakeTcpTimestampDeltaTicks", snapshot.fakeTcpTimestampDeltaTicks)
    put("fakeTlsUseOriginal", snapshot.fakeTlsUseOriginal)
    put("fakeTlsRandomize", snapshot.fakeTlsRandomize)
    put("fakeTlsDupSessionId", snapshot.fakeTlsDupSessionId)
    put("fakeTlsPadEncap", snapshot.fakeTlsPadEncap)
    put("fakeTlsSize", snapshot.fakeTlsSize)
    put("fakeTlsSniMode", snapshot.fakeTlsSniMode)
    put("ipIdMode", snapshot.ipIdMode)
    put("httpFakeProfile", snapshot.httpFakeProfile)
    put("tlsFakeProfile", snapshot.tlsFakeProfile)
    put("oobData", snapshot.oobData)
    put("dropSack", snapshot.dropSack)
    put("desyncHttp", snapshot.desyncHttp)
    put("desyncHttps", snapshot.desyncHttps)
    put("desyncUdp", snapshot.desyncUdp)
    put("hostsMode", snapshot.hostsMode)
    put("hostsBlacklist", snapshot.hostsBlacklist)
    put("hostsWhitelist", snapshot.hostsWhitelist)
    put("udpFakeProfile", snapshot.udpFakeProfile)
    put("hostMixedCase", snapshot.hostMixedCase)
    put("domainMixedCase", snapshot.domainMixedCase)
    put("hostRemoveSpaces", snapshot.hostRemoveSpaces)
    put("httpMethodEol", snapshot.httpMethodEol)
    put("httpUnixEol", snapshot.httpUnixEol)
    put("httpMethodSpace", snapshot.httpMethodSpace)
    put("httpHostPad", snapshot.httpHostPad)
    put("desyncAnyProtocol", snapshot.desyncAnyProtocol)
    putSerializable("groupActivationFilter", snapshot.groupActivationFilter)
    put("proxyAllowLan", snapshot.proxyAllowLan)
    put("appendHttpProxy", snapshot.appendHttpProxy)
    // proxyLanAuthToken is a credential and is intentionally not written to the backup.
}

internal fun JsonObject.readProxyDesyncSnapshot(
    defaults: AppSettingsProxyDesyncSnapshot,
): AppSettingsProxyDesyncSnapshot =
    AppSettingsProxyDesyncSnapshot(
        enableCommandLineSettings = booleanValue("enableCommandLineSettings", defaults.enableCommandLineSettings),
        commandLineArgs = stringValue("commandLineArgs", defaults.commandLineArgs),
        proxyIp = stringValue("proxyIp", defaults.proxyIp),
        proxyPort = intValue("proxyPort", defaults.proxyPort),
        maxConnections = intValue("maxConnections", defaults.maxConnections),
        bufferSize = intValue("bufferSize", defaults.bufferSize),
        noDomain = booleanValue("noDomain", defaults.noDomain),
        tcpFastOpen = booleanValue("tcpFastOpen", defaults.tcpFastOpen),
        mixedInboundEnabled = booleanValue("mixedInboundEnabled", defaults.mixedInboundEnabled),
        defaultTtl = intValue("defaultTtl", defaults.defaultTtl),
        customTtl = booleanValue("customTtl", defaults.customTtl),
        fakeTtl = intValue("fakeTtl", defaults.fakeTtl),
        adaptiveFakeTtlEnabled = booleanValue("adaptiveFakeTtlEnabled", defaults.adaptiveFakeTtlEnabled),
        adaptiveFakeTtlDelta = intValue("adaptiveFakeTtlDelta", defaults.adaptiveFakeTtlDelta),
        adaptiveFakeTtlMin = intValue("adaptiveFakeTtlMin", defaults.adaptiveFakeTtlMin),
        adaptiveFakeTtlMax = intValue("adaptiveFakeTtlMax", defaults.adaptiveFakeTtlMax),
        adaptiveFakeTtlFallback = intValue("adaptiveFakeTtlFallback", defaults.adaptiveFakeTtlFallback),
        fakeSni = stringValue("fakeSni", defaults.fakeSni),
        fakeOffset = intValue("fakeOffset", defaults.fakeOffset),
        fakeOffsetMarker = stringValue("fakeOffsetMarker", defaults.fakeOffsetMarker),
        fakeTlsSource = stringValue("fakeTlsSource", defaults.fakeTlsSource),
        fakeTlsSecondaryProfile = stringValue("fakeTlsSecondaryProfile", defaults.fakeTlsSecondaryProfile),
        fakeTcpTimestampEnabled = booleanValue("fakeTcpTimestampEnabled", defaults.fakeTcpTimestampEnabled),
        fakeTcpTimestampDeltaTicks = intValue("fakeTcpTimestampDeltaTicks", defaults.fakeTcpTimestampDeltaTicks),
        fakeTlsUseOriginal = booleanValue("fakeTlsUseOriginal", defaults.fakeTlsUseOriginal),
        fakeTlsRandomize = booleanValue("fakeTlsRandomize", defaults.fakeTlsRandomize),
        fakeTlsDupSessionId = booleanValue("fakeTlsDupSessionId", defaults.fakeTlsDupSessionId),
        fakeTlsPadEncap = booleanValue("fakeTlsPadEncap", defaults.fakeTlsPadEncap),
        fakeTlsSize = intValue("fakeTlsSize", defaults.fakeTlsSize),
        fakeTlsSniMode = stringValue("fakeTlsSniMode", defaults.fakeTlsSniMode),
        ipIdMode = stringValue("ipIdMode", defaults.ipIdMode),
        httpFakeProfile = stringValue("httpFakeProfile", defaults.httpFakeProfile),
        tlsFakeProfile = stringValue("tlsFakeProfile", defaults.tlsFakeProfile),
        oobData = stringValue("oobData", defaults.oobData),
        dropSack = booleanValue("dropSack", defaults.dropSack),
        desyncHttp = booleanValue("desyncHttp", defaults.desyncHttp),
        desyncHttps = booleanValue("desyncHttps", defaults.desyncHttps),
        desyncUdp = booleanValue("desyncUdp", defaults.desyncUdp),
        hostsMode = stringValue("hostsMode", defaults.hostsMode),
        hostsBlacklist = stringValue("hostsBlacklist", defaults.hostsBlacklist),
        hostsWhitelist = stringValue("hostsWhitelist", defaults.hostsWhitelist),
        udpFakeProfile = stringValue("udpFakeProfile", defaults.udpFakeProfile),
        hostMixedCase = booleanValue("hostMixedCase", defaults.hostMixedCase),
        domainMixedCase = booleanValue("domainMixedCase", defaults.domainMixedCase),
        hostRemoveSpaces = booleanValue("hostRemoveSpaces", defaults.hostRemoveSpaces),
        httpMethodEol = booleanValue("httpMethodEol", defaults.httpMethodEol),
        httpUnixEol = booleanValue("httpUnixEol", defaults.httpUnixEol),
        httpMethodSpace = booleanValue("httpMethodSpace", defaults.httpMethodSpace),
        httpHostPad = booleanValue("httpHostPad", defaults.httpHostPad),
        desyncAnyProtocol = booleanValue("desyncAnyProtocol", defaults.desyncAnyProtocol),
        groupActivationFilter =
            serializableValue("groupActivationFilter", defaults.groupActivationFilter),
        proxyAllowLan = booleanValue("proxyAllowLan", defaults.proxyAllowLan),
        proxyLanAuthToken = "",
        appendHttpProxy = booleanValue("appendHttpProxy", defaults.appendHttpProxy),
    )
