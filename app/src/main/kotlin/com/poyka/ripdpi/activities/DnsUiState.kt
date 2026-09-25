package com.poyka.ripdpi.activities

import androidx.compose.runtime.Stable
import com.poyka.ripdpi.data.EncryptedDnsOdohConfigSourceBundled
import com.poyka.ripdpi.data.EncryptedDnsOdohConfigSourceCustomBytes
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings
import com.poyka.ripdpi.diagnostics.SystemPrivateDnsStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import java.net.URI

private val DefaultDnsUiSeed = canonicalDefaultEncryptedDnsSettings()
private const val HexRadix = 16
private const val MaxDnsPort = 65_535
internal const val LegacyOdohConfigSourceCustom = "custom"

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
    val odoh: OdohResolverFields = OdohResolverFields(),
    val dnsSummary: String = DefaultDnsUiSeed.summary(),
    // Informational-only: whether the Android system "Private DNS" feature is
    // configured. Never a VPN DNS policy source; used to surface an educational
    // note (and a conditional warning) in the DNS settings screen.
    val systemPrivateDnsStatus: String = SystemPrivateDnsStatus.UNKNOWN.wireValue,
)

@Stable
data class OdohResolverFields(
    val proxyUrl: String = "",
    val proxyOperatorId: String = "",
    val targetHost: String = "",
    val targetPath: String = "",
    val targetOperatorId: String = "",
    val configSource: String = "",
    val configsHex: String = "",
    val configsRetrievedAtSecs: Long = 0L,
    val configsTtlSecs: Long = 0L,
)

fun OdohResolverFields.isValid(): Boolean {
    val url = runCatching { URI(proxyUrl.trim()) }.getOrNull()
    return url != null &&
        url.scheme.equals("https", ignoreCase = true) &&
        !url.host.isNullOrBlank() &&
        (url.port == -1 || url.port in 1..MaxDnsPort) &&
        proxyOperatorId.isNotBlank() &&
        targetHost.isNotBlank() &&
        targetPath.startsWith('/') && targetPath.length > 1 &&
        targetOperatorId.isNotBlank() &&
        !proxyOperatorId.equals(targetOperatorId, ignoreCase = true) &&
        (
            configSource == EncryptedDnsOdohConfigSourceBundled ||
                configSource == EncryptedDnsOdohConfigSourceCustomBytes ||
                configSource == LegacyOdohConfigSourceCustom
        ) &&
        configsHex.isNotBlank() && configsHex.length % 2 == 0 &&
        configsHex.all { it.digitToIntOrNull(HexRadix) != null } &&
        configsRetrievedAtSecs > 0 && configsTtlSecs > 0
}
