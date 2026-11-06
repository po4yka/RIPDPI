package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable

@Serializable
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
