package com.poyka.ripdpi.ui.screens.dns

import androidx.annotation.StringRes
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

internal const val dnsPortWeightFraction = 0.4f

internal data class DnsResolverOption(
    val providerId: String,
    val protocol: String,
    val address: String,
    val host: String,
    val port: Int,
    val tlsServerName: String,
    val dohUrl: String,
    val bootstrapIps: ImmutableList<String>,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
)

internal val resolverOptions =
    listOf(
        DnsResolverOption(
            providerId = DnsProviderCloudflare,
            protocol = EncryptedDnsProtocolDoh,
            address = "1.1.1.1",
            host = "cloudflare-dns.com",
            port = 443,
            tlsServerName = "cloudflare-dns.com",
            dohUrl = "https://cloudflare-dns.com/dns-query",
            bootstrapIps = persistentListOf("1.1.1.1", "1.0.0.1"),
            titleRes = R.string.dns_resolver_cloudflare_title,
            descriptionRes = R.string.dns_resolver_cloudflare_body,
        ),
        DnsResolverOption(
            providerId = "google",
            protocol = EncryptedDnsProtocolDoh,
            address = "8.8.8.8",
            host = "dns.google",
            port = 443,
            tlsServerName = "dns.google",
            dohUrl = "https://dns.google/dns-query",
            bootstrapIps = persistentListOf("8.8.8.8", "8.8.4.4"),
            titleRes = R.string.dns_resolver_google_title,
            descriptionRes = R.string.dns_resolver_google_body,
        ),
        DnsResolverOption(
            providerId = "quad9",
            protocol = EncryptedDnsProtocolDoh,
            address = "9.9.9.9",
            host = "dns.quad9.net",
            port = 443,
            tlsServerName = "dns.quad9.net",
            dohUrl = "https://dns.quad9.net/dns-query",
            bootstrapIps = persistentListOf("9.9.9.9", "149.112.112.112"),
            titleRes = R.string.dns_resolver_quad9_title,
            descriptionRes = R.string.dns_resolver_quad9_body,
        ),
        DnsResolverOption(
            providerId = "adguard",
            protocol = EncryptedDnsProtocolDoh,
            address = "94.140.14.14",
            host = "dns.adguard-dns.com",
            port = 443,
            tlsServerName = "dns.adguard-dns.com",
            dohUrl = "https://dns.adguard-dns.com/dns-query",
            bootstrapIps = persistentListOf("94.140.14.14", "94.140.15.15"),
            titleRes = R.string.dns_resolver_adguard_title,
            descriptionRes = R.string.dns_resolver_adguard_body,
        ),
    )
