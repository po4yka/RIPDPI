package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val DnsModePlainUdp = "plain_udp"
const val DnsModeDoh = "doh"

const val DnsProviderCloudflare = "cloudflare"
const val DnsProviderGoogle = "google"
const val DnsProviderQuad9 = "quad9"
const val DnsProviderAdGuard = "adguard"
const val DnsProviderCustom = "custom"

data class DnsProviderDefinition(
    val providerId: String,
    val displayName: String,
    val primaryIp: String,
    val dohUrl: String,
    val bootstrapIps: List<String>,
)

data class ActiveDnsSettings(
    val mode: String,
    val providerId: String,
    val dnsIp: String,
    val dohUrl: String,
    val dohBootstrapIps: List<String>,
) {
    val isDoh: Boolean
        get() = mode == DnsModeDoh

    val isPlainUdp: Boolean
        get() = mode == DnsModePlainUdp

    val providerDisplayName: String
        get() = dnsProviderById(providerId)?.displayName ?: "Custom"

    fun summary(): String =
        if (isDoh) {
            "DoH · $providerDisplayName"
        } else {
            "Plain DNS · $dnsIp"
        }
}

val BuiltInDnsProviders: List<DnsProviderDefinition> =
    listOf(
        DnsProviderDefinition(
            providerId = DnsProviderCloudflare,
            displayName = "Cloudflare",
            primaryIp = "1.1.1.1",
            dohUrl = "https://cloudflare-dns.com/dns-query",
            bootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
        ),
        DnsProviderDefinition(
            providerId = DnsProviderGoogle,
            displayName = "Google Public DNS",
            primaryIp = "8.8.8.8",
            dohUrl = "https://dns.google/dns-query",
            bootstrapIps = listOf("8.8.8.8", "8.8.4.4"),
        ),
        DnsProviderDefinition(
            providerId = DnsProviderQuad9,
            displayName = "Quad9",
            primaryIp = "9.9.9.9",
            dohUrl = "https://dns.quad9.net/dns-query",
            bootstrapIps = listOf("9.9.9.9", "149.112.112.112"),
        ),
        DnsProviderDefinition(
            providerId = DnsProviderAdGuard,
            displayName = "AdGuard DNS",
            primaryIp = "94.140.14.14",
            dohUrl = "https://dns.adguard-dns.com/dns-query",
            bootstrapIps = listOf("94.140.14.14", "94.140.15.15"),
        ),
    )

fun dnsProviderById(providerId: String): DnsProviderDefinition? =
    BuiltInDnsProviders.firstOrNull { it.providerId == providerId }

fun normalizeDnsBootstrapIps(values: Iterable<String>): List<String> =
    values
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

private fun legacyDnsSettings(dnsIp: String): ActiveDnsSettings {
    val normalizedDnsIp = dnsIp.ifBlank { AppSettingsSerializer.defaultValue.dnsIp }
    val builtIn = BuiltInDnsProviders.firstOrNull { it.primaryIp == normalizedDnsIp }
    return if (builtIn != null) {
        ActiveDnsSettings(
            mode = DnsModeDoh,
            providerId = builtIn.providerId,
            dnsIp = builtIn.primaryIp,
            dohUrl = builtIn.dohUrl,
            dohBootstrapIps = builtIn.bootstrapIps,
        )
    } else {
        ActiveDnsSettings(
            mode = DnsModePlainUdp,
            providerId = DnsProviderCustom,
            dnsIp = normalizedDnsIp,
            dohUrl = "",
            dohBootstrapIps = emptyList(),
        )
    }
}

fun activeDnsSettings(
    dnsMode: String,
    dnsProviderId: String,
    dnsIp: String,
    dnsDohUrl: String,
    dnsDohBootstrapIps: Iterable<String>,
): ActiveDnsSettings {
    val normalizedMode = dnsMode.trim()
    val normalizedProviderId = dnsProviderId.trim()
    if (normalizedMode.isBlank()) {
        return legacyDnsSettings(dnsIp)
    }

    if (normalizedMode != DnsModeDoh) {
        return ActiveDnsSettings(
            mode = DnsModePlainUdp,
            providerId = DnsProviderCustom,
            dnsIp = dnsIp.ifBlank { AppSettingsSerializer.defaultValue.dnsIp },
            dohUrl = "",
            dohBootstrapIps = emptyList(),
        )
    }

    val builtIn = dnsProviderById(normalizedProviderId)
    if (builtIn != null) {
        return ActiveDnsSettings(
            mode = DnsModeDoh,
            providerId = builtIn.providerId,
            dnsIp = builtIn.primaryIp,
            dohUrl = builtIn.dohUrl,
            dohBootstrapIps = builtIn.bootstrapIps,
        )
    }

    val normalizedBootstrapIps = normalizeDnsBootstrapIps(dnsDohBootstrapIps)
    val effectiveDnsIp =
        normalizedBootstrapIps.firstOrNull()
            ?: dnsIp.ifBlank { AppSettingsSerializer.defaultValue.dnsIp }
    return ActiveDnsSettings(
        mode = DnsModeDoh,
        providerId = DnsProviderCustom,
        dnsIp = effectiveDnsIp,
        dohUrl = dnsDohUrl.trim(),
        dohBootstrapIps = normalizedBootstrapIps,
    )
}

fun AppSettings.activeDnsSettings(): ActiveDnsSettings =
    activeDnsSettings(
        dnsMode = dnsMode,
        dnsProviderId = dnsProviderId,
        dnsIp = dnsIp,
        dnsDohUrl = dnsDohUrl,
        dnsDohBootstrapIps = dnsDohBootstrapIpsList,
    )
