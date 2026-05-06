package com.poyka.ripdpi.activities

import androidx.compose.runtime.Stable
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings

private val DefaultDnsUiSeed = canonicalDefaultEncryptedDnsSettings()

@Stable
data class DnsUiState(
    val dnsIp: String = DefaultDnsUiSeed.dnsIp,
    val dnsMode: String = DefaultDnsUiSeed.mode,
    val dnsProviderId: String = DefaultDnsUiSeed.providerId,
    val encryptedDnsProtocol: String = DefaultDnsUiSeed.encryptedDnsProtocol,
    val encryptedDnsHost: String = DefaultDnsUiSeed.encryptedDnsHost,
    val encryptedDnsPort: Int = DefaultDnsUiSeed.encryptedDnsPort,
    val encryptedDnsTlsServerName: String = DefaultDnsUiSeed.encryptedDnsTlsServerName,
    val encryptedDnsBootstrapIps: List<String> = DefaultDnsUiSeed.encryptedDnsBootstrapIps,
    val encryptedDnsDohUrl: String = DefaultDnsUiSeed.encryptedDnsDohUrl,
    val encryptedDnsDnscryptProviderName: String = "",
    val encryptedDnsDnscryptPublicKey: String = "",
    val dnsSummary: String = DefaultDnsUiSeed.summary(),
)
