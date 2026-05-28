package com.poyka.ripdpi.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

internal data class AppSettingsDnsSnapshot(
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
    val encryptedDnsOdohProxyUrl: String = "",
    val encryptedDnsOdohProxyOperatorId: String = "",
    val encryptedDnsOdohTargetHost: String = "",
    val encryptedDnsOdohTargetPath: String = "",
    val encryptedDnsOdohTargetOperatorId: String = "",
    val encryptedDnsOdohConfigSource: String = "",
    val encryptedDnsOdohConfigsHex: String = "",
    val encryptedDnsOdohConfigsRetrievedAtSecs: Long = 0L,
    val encryptedDnsOdohConfigsTtlSecs: Long = 0L,
    val ipv6Enabled: Boolean = defaultSettings.ipv6Enable,
)

internal fun JsonObjectBuilder.writeDnsSnapshot(snapshot: AppSettingsDnsSnapshot) {
    put("dnsIp", snapshot.dnsIp)
    put("dnsMode", snapshot.dnsMode)
    put("dnsProviderId", snapshot.dnsProviderId)
    put("encryptedDnsProtocol", snapshot.encryptedDnsProtocol)
    put("encryptedDnsHost", snapshot.encryptedDnsHost)
    put("encryptedDnsPort", snapshot.encryptedDnsPort)
    put("encryptedDnsTlsServerName", snapshot.encryptedDnsTlsServerName)
    putStringList("encryptedDnsBootstrapIps", snapshot.encryptedDnsBootstrapIps)
    put("encryptedDnsDohUrl", snapshot.encryptedDnsDohUrl)
    put("encryptedDnsDnscryptProviderName", snapshot.encryptedDnsDnscryptProviderName)
    put("encryptedDnsDnscryptPublicKey", snapshot.encryptedDnsDnscryptPublicKey)
    put("encryptedDnsOdohProxyUrl", snapshot.encryptedDnsOdohProxyUrl)
    put("encryptedDnsOdohProxyOperatorId", snapshot.encryptedDnsOdohProxyOperatorId)
    put("encryptedDnsOdohTargetHost", snapshot.encryptedDnsOdohTargetHost)
    put("encryptedDnsOdohTargetPath", snapshot.encryptedDnsOdohTargetPath)
    put("encryptedDnsOdohTargetOperatorId", snapshot.encryptedDnsOdohTargetOperatorId)
    put("encryptedDnsOdohConfigSource", snapshot.encryptedDnsOdohConfigSource)
    put("encryptedDnsOdohConfigsHex", snapshot.encryptedDnsOdohConfigsHex)
    put("encryptedDnsOdohConfigsRetrievedAtSecs", snapshot.encryptedDnsOdohConfigsRetrievedAtSecs)
    put("encryptedDnsOdohConfigsTtlSecs", snapshot.encryptedDnsOdohConfigsTtlSecs)
    put("ipv6Enabled", snapshot.ipv6Enabled)
}

internal fun JsonObject.readDnsSnapshot(defaults: AppSettingsDnsSnapshot): AppSettingsDnsSnapshot =
    AppSettingsDnsSnapshot(
        dnsIp = stringValue("dnsIp", defaults.dnsIp),
        dnsMode = stringValue("dnsMode", defaults.dnsMode),
        dnsProviderId = stringValue("dnsProviderId", defaults.dnsProviderId),
        encryptedDnsProtocol = stringValue("encryptedDnsProtocol", defaults.encryptedDnsProtocol),
        encryptedDnsHost = stringValue("encryptedDnsHost", defaults.encryptedDnsHost),
        encryptedDnsPort = intValue("encryptedDnsPort", defaults.encryptedDnsPort),
        encryptedDnsTlsServerName = stringValue("encryptedDnsTlsServerName", defaults.encryptedDnsTlsServerName),
        encryptedDnsBootstrapIps = stringListValue("encryptedDnsBootstrapIps", defaults.encryptedDnsBootstrapIps),
        encryptedDnsDohUrl = stringValue("encryptedDnsDohUrl", defaults.encryptedDnsDohUrl),
        encryptedDnsDnscryptProviderName =
            stringValue("encryptedDnsDnscryptProviderName", defaults.encryptedDnsDnscryptProviderName),
        encryptedDnsDnscryptPublicKey =
            stringValue("encryptedDnsDnscryptPublicKey", defaults.encryptedDnsDnscryptPublicKey),
        encryptedDnsOdohProxyUrl = stringValue("encryptedDnsOdohProxyUrl", defaults.encryptedDnsOdohProxyUrl),
        encryptedDnsOdohProxyOperatorId =
            stringValue("encryptedDnsOdohProxyOperatorId", defaults.encryptedDnsOdohProxyOperatorId),
        encryptedDnsOdohTargetHost = stringValue("encryptedDnsOdohTargetHost", defaults.encryptedDnsOdohTargetHost),
        encryptedDnsOdohTargetPath = stringValue("encryptedDnsOdohTargetPath", defaults.encryptedDnsOdohTargetPath),
        encryptedDnsOdohTargetOperatorId =
            stringValue("encryptedDnsOdohTargetOperatorId", defaults.encryptedDnsOdohTargetOperatorId),
        encryptedDnsOdohConfigSource =
            stringValue("encryptedDnsOdohConfigSource", defaults.encryptedDnsOdohConfigSource),
        encryptedDnsOdohConfigsHex = stringValue("encryptedDnsOdohConfigsHex", defaults.encryptedDnsOdohConfigsHex),
        encryptedDnsOdohConfigsRetrievedAtSecs =
            longValue("encryptedDnsOdohConfigsRetrievedAtSecs", defaults.encryptedDnsOdohConfigsRetrievedAtSecs),
        encryptedDnsOdohConfigsTtlSecs =
            longValue("encryptedDnsOdohConfigsTtlSecs", defaults.encryptedDnsOdohConfigsTtlSecs),
        ipv6Enabled = booleanValue("ipv6Enabled", defaults.ipv6Enabled),
    )
