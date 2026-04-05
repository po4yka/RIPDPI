package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import java.net.URI

const val DnsModePlainUdp = "plain_udp"
const val DnsModeEncrypted = "encrypted"

private const val DefaultHttpsPort = 443
private const val DefaultHttpPort = 80
private const val DefaultDoTPort = 853

const val EncryptedDnsProtocolDoh = "doh"
const val EncryptedDnsProtocolDot = "dot"
const val EncryptedDnsProtocolDnsCrypt = "dnscrypt"
const val EncryptedDnsProtocolDoq = "doq"

const val DnsProviderCloudflare = "cloudflare"
const val DnsProviderGoogle = "google"
const val DnsProviderQuad9 = "quad9"
const val DnsProviderAdGuard = "adguard"
const val DnsProviderDnsSb = "dnssb"
const val DnsProviderMullvad = "mullvad"
const val DnsProviderCloudflareIp = "cloudflare_ip"
const val DnsProviderGoogleIp = "google_ip"
const val DnsProviderCustom = "custom"

data class DnsProviderDefinition(
    val providerId: String,
    val displayName: String,
    val primaryIp: String,
    val protocol: String = EncryptedDnsProtocolDoh,
    val host: String,
    val port: Int,
    val tlsServerName: String,
    val bootstrapIps: List<String>,
    val dohUrl: String? = null,
    val dnscryptProviderName: String? = null,
    val dnscryptPublicKey: String? = null,
)

data class ActiveDnsSettings(
    val mode: String,
    val providerId: String,
    val dnsIp: String,
    val encryptedDnsProtocol: String,
    val encryptedDnsHost: String,
    val encryptedDnsPort: Int,
    val encryptedDnsTlsServerName: String,
    val encryptedDnsBootstrapIps: List<String>,
    val encryptedDnsDohUrl: String,
    val encryptedDnsDnscryptProviderName: String,
    val encryptedDnsDnscryptPublicKey: String,
) {
    val isEncrypted: Boolean
        get() = mode == DnsModeEncrypted

    val isPlainUdp: Boolean
        get() = mode == DnsModePlainUdp

    val isDoh: Boolean
        get() = isEncrypted && encryptedDnsProtocol == EncryptedDnsProtocolDoh

    val isDot: Boolean
        get() = isEncrypted && encryptedDnsProtocol == EncryptedDnsProtocolDot

    val isDnsCrypt: Boolean
        get() = isEncrypted && encryptedDnsProtocol == EncryptedDnsProtocolDnsCrypt

    val isDoq: Boolean
        get() = isEncrypted && encryptedDnsProtocol == EncryptedDnsProtocolDoq

    val dohUrl: String
        get() = encryptedDnsDohUrl

    val dohBootstrapIps: List<String>
        get() = encryptedDnsBootstrapIps

    val providerDisplayName: String
        get() = dnsProviderById(providerId)?.displayName ?: "Custom resolver"

    fun summary(): String =
        if (isEncrypted) {
            "Encrypted DNS · $providerDisplayName (${protocolDisplayName(encryptedDnsProtocol)})"
        } else {
            "Plain DNS · $dnsIp"
        }
}

val BuiltInDnsProviders: List<DnsProviderDefinition> =
    listOf(
        DnsProviderDefinition(
            providerId = DnsProviderAdGuard,
            displayName = "AdGuard DNS",
            primaryIp = "94.140.14.14",
            host = "dns.adguard-dns.com",
            port = 443,
            tlsServerName = "dns.adguard-dns.com",
            bootstrapIps = listOf("94.140.14.14", "94.140.15.15"),
            dohUrl = "https://dns.adguard-dns.com/dns-query",
        ),
        DnsProviderDefinition(
            providerId = DnsProviderDnsSb,
            displayName = "DNS.SB",
            primaryIp = "185.222.222.222",
            host = "dns.sb",
            port = 443,
            tlsServerName = "dns.sb",
            bootstrapIps = listOf("185.222.222.222", "45.11.45.11"),
            dohUrl = "https://doh.dns.sb/dns-query",
        ),
        DnsProviderDefinition(
            providerId = DnsProviderMullvad,
            displayName = "Mullvad DNS",
            primaryIp = "194.242.2.2",
            host = "dns.mullvad.net",
            port = 443,
            tlsServerName = "dns.mullvad.net",
            bootstrapIps = listOf("194.242.2.2"),
            dohUrl = "https://dns.mullvad.net/dns-query",
        ),
        DnsProviderDefinition(
            providerId = DnsProviderGoogleIp,
            displayName = "Google DNS (IP)",
            primaryIp = "8.8.8.8",
            host = "8.8.8.8",
            port = 443,
            tlsServerName = "dns.google",
            bootstrapIps = listOf("8.8.8.8", "8.8.4.4"),
            dohUrl = "https://8.8.8.8/dns-query",
        ),
        DnsProviderDefinition(
            providerId = DnsProviderCloudflareIp,
            displayName = "Cloudflare (IP)",
            primaryIp = "1.1.1.1",
            host = "1.1.1.1",
            port = 443,
            tlsServerName = "cloudflare-dns.com",
            bootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
            dohUrl = "https://1.1.1.1/dns-query",
        ),
        DnsProviderDefinition(
            providerId = DnsProviderGoogle,
            displayName = "Google Public DNS",
            primaryIp = "8.8.8.8",
            host = "dns.google",
            port = 443,
            tlsServerName = "dns.google",
            bootstrapIps = listOf("8.8.8.8", "8.8.4.4"),
            dohUrl = "https://dns.google/dns-query",
        ),
        DnsProviderDefinition(
            providerId = DnsProviderQuad9,
            displayName = "Quad9",
            primaryIp = "9.9.9.9",
            host = "dns.quad9.net",
            port = 443,
            tlsServerName = "dns.quad9.net",
            bootstrapIps = listOf("9.9.9.9", "149.112.112.112"),
            dohUrl = "https://dns.quad9.net/dns-query",
        ),
        DnsProviderDefinition(
            providerId = DnsProviderCloudflare,
            displayName = "Cloudflare",
            primaryIp = "1.1.1.1",
            host = "cloudflare-dns.com",
            port = 443,
            tlsServerName = "cloudflare-dns.com",
            bootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
            dohUrl = "https://cloudflare-dns.com/dns-query",
        ),
    )

fun canonicalDefaultDnsProviderDefinition(): DnsProviderDefinition =
    BuiltInDnsProviders.firstOrNull() ?: error("BuiltInDnsProviders must not be empty")

fun canonicalDefaultPlainDnsIp(): String = canonicalDefaultDnsProviderDefinition().primaryIp

fun canonicalDefaultUdpDnsServer(): String = "${canonicalDefaultPlainDnsIp()}:53"

fun canonicalDefaultEncryptedDnsSettings(): ActiveDnsSettings =
    defaultEncryptedSettingsForBuiltIn(canonicalDefaultDnsProviderDefinition())

fun canonicalDefaultEncryptedDnsPathCandidate(): EncryptedDnsPathCandidate =
    requireNotNull(canonicalDefaultEncryptedDnsSettings().toEncryptedDnsPathCandidate())

fun dnsProviderById(providerId: String): DnsProviderDefinition? =
    BuiltInDnsProviders.firstOrNull { it.providerId == providerId }

fun normalizeDnsBootstrapIps(values: Iterable<String>): List<String> =
    values
        .flatMap { entry -> entry.split(',', ' ', '\n', '\t') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

fun protocolDisplayName(protocol: String): String =
    when (protocol) {
        EncryptedDnsProtocolDot -> "DoT"
        EncryptedDnsProtocolDnsCrypt -> "DNSCrypt"
        EncryptedDnsProtocolDoq -> "DoQ"
        else -> "DoH"
    }

private fun firstNonBlank(vararg values: String?): String = values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

private fun parseHostFromUrl(value: String): String = runCatching { URI(value).host.orEmpty() }.getOrDefault("")

private fun parsePortFromUrl(value: String): Int =
    runCatching {
        val uri = URI(value)
        if (uri.port > 0) {
            uri.port
        } else {
            when (uri.scheme?.lowercase()) {
                "https" -> DefaultHttpsPort
                "http" -> DefaultHttpPort
                else -> 0
            }
        }
    }.getOrDefault(0)

private fun defaultEncryptedSettingsForBuiltIn(provider: DnsProviderDefinition): ActiveDnsSettings =
    ActiveDnsSettings(
        mode = DnsModeEncrypted,
        providerId = provider.providerId,
        dnsIp = provider.primaryIp,
        encryptedDnsProtocol = provider.protocol,
        encryptedDnsHost = provider.host,
        encryptedDnsPort = provider.port,
        encryptedDnsTlsServerName = provider.tlsServerName,
        encryptedDnsBootstrapIps = provider.bootstrapIps,
        encryptedDnsDohUrl = provider.dohUrl.orEmpty(),
        encryptedDnsDnscryptProviderName = provider.dnscryptProviderName.orEmpty(),
        encryptedDnsDnscryptPublicKey = provider.dnscryptPublicKey.orEmpty(),
    )

private fun plainDnsSettings(dnsIp: String): ActiveDnsSettings =
    ActiveDnsSettings(
        mode = DnsModePlainUdp,
        providerId = DnsProviderCustom,
        dnsIp = dnsIp.ifBlank { canonicalDefaultPlainDnsIp() },
        encryptedDnsProtocol = "",
        encryptedDnsHost = "",
        encryptedDnsPort = 0,
        encryptedDnsTlsServerName = "",
        encryptedDnsBootstrapIps = emptyList(),
        encryptedDnsDohUrl = "",
        encryptedDnsDnscryptProviderName = "",
        encryptedDnsDnscryptPublicKey = "",
    )

private fun normalizedEncryptedProtocol(encryptedDnsProtocol: String): String =
    when {
        encryptedDnsProtocol.equals(EncryptedDnsProtocolDot, ignoreCase = true) -> EncryptedDnsProtocolDot
        encryptedDnsProtocol.equals(EncryptedDnsProtocolDnsCrypt, ignoreCase = true) -> EncryptedDnsProtocolDnsCrypt
        encryptedDnsProtocol.equals(EncryptedDnsProtocolDoq, ignoreCase = true) -> EncryptedDnsProtocolDoq
        else -> EncryptedDnsProtocolDoh
    }

@Suppress("CyclomaticComplexMethod", "ReturnCount")
fun activeDnsSettings(
    dnsMode: String,
    dnsProviderId: String,
    dnsIp: String,
    encryptedDnsProtocol: String = "",
    encryptedDnsHost: String = "",
    encryptedDnsPort: Int = 0,
    encryptedDnsTlsServerName: String = "",
    encryptedDnsBootstrapIps: Iterable<String> = emptyList(),
    encryptedDnsDohUrl: String = "",
    encryptedDnsDnscryptProviderName: String = "",
    encryptedDnsDnscryptPublicKey: String = "",
): ActiveDnsSettings {
    val normalizedMode = dnsMode.trim()
    val normalizedProviderId = dnsProviderId.trim()
    if (normalizedMode.isBlank()) {
        return canonicalDefaultEncryptedDnsSettings()
    }

    if (normalizedMode != DnsModeEncrypted) {
        return plainDnsSettings(dnsIp)
    }

    val normalizedProtocol = normalizedEncryptedProtocol(encryptedDnsProtocol.trim())

    val builtIn = dnsProviderById(normalizedProviderId)
    if (builtIn != null && normalizedProtocol == builtIn.protocol) {
        return defaultEncryptedSettingsForBuiltIn(builtIn)
    }

    val normalizedBootstrapIps =
        normalizeDnsBootstrapIps(
            if (encryptedDnsBootstrapIps.any()) {
                encryptedDnsBootstrapIps
            } else {
                builtIn?.bootstrapIps.orEmpty()
            },
        )
    val effectiveDnsIp =
        normalizedBootstrapIps.firstOrNull()
            ?: dnsIp.ifBlank { canonicalDefaultPlainDnsIp() }
    val effectiveDohUrl = encryptedDnsDohUrl.trim()
    val derivedHost =
        when (normalizedProtocol) {
            EncryptedDnsProtocolDoh -> firstNonBlank(parseHostFromUrl(effectiveDohUrl), builtIn?.host)
            else -> builtIn?.host.orEmpty()
        }
    val effectiveHost = firstNonBlank(encryptedDnsHost, derivedHost)
    val effectivePort =
        when {
            encryptedDnsPort > 0 -> {
                encryptedDnsPort
            }

            builtIn != null && normalizedProtocol == EncryptedDnsProtocolDoh -> {
                builtIn.port
            }

            normalizedProtocol == EncryptedDnsProtocolDoh -> {
                parsePortFromUrl(effectiveDohUrl).takeIf { it > 0 } ?: DefaultHttpsPort
            }

            normalizedProtocol == EncryptedDnsProtocolDot -> {
                DefaultDoTPort
            }

            normalizedProtocol == EncryptedDnsProtocolDoq -> {
                DefaultDoTPort
            }

            else -> {
                DefaultHttpsPort
            }
        }
    val effectiveTlsServerName =
        firstNonBlank(
            encryptedDnsTlsServerName,
            builtIn?.tlsServerName,
            when {
                normalizedProtocol == EncryptedDnsProtocolDot ||
                    normalizedProtocol == EncryptedDnsProtocolDoh ||
                    normalizedProtocol == EncryptedDnsProtocolDoq -> effectiveHost

                else -> ""
            },
        )

    return ActiveDnsSettings(
        mode = DnsModeEncrypted,
        providerId = normalizedProviderId.ifBlank { DnsProviderCustom },
        dnsIp = effectiveDnsIp,
        encryptedDnsProtocol = normalizedProtocol,
        encryptedDnsHost = effectiveHost,
        encryptedDnsPort = effectivePort,
        encryptedDnsTlsServerName = effectiveTlsServerName,
        encryptedDnsBootstrapIps = normalizedBootstrapIps,
        encryptedDnsDohUrl = if (normalizedProtocol == EncryptedDnsProtocolDoh) effectiveDohUrl else "",
        encryptedDnsDnscryptProviderName =
            if (normalizedProtocol == EncryptedDnsProtocolDnsCrypt) {
                encryptedDnsDnscryptProviderName.trim()
            } else {
                ""
            },
        encryptedDnsDnscryptPublicKey =
            if (normalizedProtocol == EncryptedDnsProtocolDnsCrypt) {
                encryptedDnsDnscryptPublicKey.trim()
            } else {
                ""
            },
    )
}

fun AppSettings.activeDnsSettings(): ActiveDnsSettings =
    activeDnsSettings(
        dnsMode = dnsMode,
        dnsProviderId = dnsProviderId,
        dnsIp = dnsIp,
        encryptedDnsProtocol = encryptedDnsProtocol,
        encryptedDnsHost = encryptedDnsHost,
        encryptedDnsPort = encryptedDnsPort,
        encryptedDnsTlsServerName = encryptedDnsTlsServerName,
        encryptedDnsBootstrapIps = encryptedDnsBootstrapIpsList,
        encryptedDnsDohUrl = encryptedDnsDohUrl,
        encryptedDnsDnscryptProviderName = encryptedDnsDnscryptProviderName,
        encryptedDnsDnscryptPublicKey = encryptedDnsDnscryptPublicKey,
    )
