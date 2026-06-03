package com.poyka.ripdpi.ui.screens.dns

import androidx.annotation.StringRes
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.BundledDnsProviderCatalog
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

internal const val dnsPortWeightFraction = 0.4f

/**
 * One row in the primary VPN DNS picker. Curated rows carry localized [titleRes]/[descriptionRes];
 * catalog-derived rows instead carry the provider's proper name in [title] (proper names are not
 * translated) and reuse a generic [descriptionRes]. [jurisdictionRu] drives the RU-jurisdiction
 * caption rendered below the description.
 */
internal data class DnsResolverOption(
    val providerId: String,
    val protocol: String,
    val address: String,
    val host: String,
    val port: Int,
    val tlsServerName: String,
    val dohUrl: String,
    val bootstrapIps: ImmutableList<String>,
    @param:StringRes val titleRes: Int? = null,
    @param:StringRes val descriptionRes: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val jurisdictionRu: Boolean = false,
)

/**
 * The four curated, hand-localized resolver rows. Kept byte-identical so their screenshots do not
 * churn; the remaining catalog providers are appended in [resolverOptions].
 */
private val curatedResolverOptions =
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

/**
 * The full resolver picker: the four curated rows followed by every other DoH-capable bundled
 * catalog provider. Catalog-derived rows carry the provider's proper name (not translated) and a
 * generic localized description. A selection persists because the prior data layer's
 * `dnsProviderById` falls back to the bundled catalog for catalog-only ids.
 */
internal val resolverOptions: List<DnsResolverOption> =
    curatedResolverOptions +
        BundledDnsProviderCatalog.providers
            .filter { entry -> entry.supportsDoh && curatedResolverOptions.none { it.providerId == entry.id } }
            .map { entry ->
                DnsResolverOption(
                    providerId = entry.id,
                    protocol = EncryptedDnsProtocolDoh,
                    address = entry.bootstrapIps.firstOrNull().orEmpty(),
                    host = entry.dotHost,
                    port = entry.port,
                    tlsServerName = entry.tlsServerName.ifBlank { entry.dotHost },
                    dohUrl = entry.dohUrl,
                    bootstrapIps = entry.bootstrapIps.toImmutableList(),
                    title = entry.name,
                    descriptionRes = R.string.dns_resolver_catalog_generic_body,
                    jurisdictionRu = entry.rf.jurisdictionRu,
                )
            }
