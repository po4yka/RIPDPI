package com.poyka.ripdpi.activities

import androidx.compose.runtime.Stable
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings
import com.poyka.ripdpi.diagnostics.SystemPrivateDnsStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

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
    val encryptedDnsBootstrapIps: ImmutableList<String> = DefaultDnsUiSeed.encryptedDnsBootstrapIps.toImmutableList(),
    val encryptedDnsDohUrl: String = DefaultDnsUiSeed.encryptedDnsDohUrl,
    val encryptedDnsDnscryptProviderName: String = "",
    val encryptedDnsDnscryptPublicKey: String = "",
    val dnsSummary: String = DefaultDnsUiSeed.summary(),
    // Informational-only: whether the Android system "Private DNS" feature is
    // configured. Never a VPN DNS policy source; used to surface an educational
    // note (and a conditional warning) in the DNS settings screen.
    val systemPrivateDnsStatus: String = SystemPrivateDnsStatus.UNKNOWN.wireValue,
)
