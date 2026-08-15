package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

const val DnsProviderCatalogSchemaVersion = 1

/** Default port shared by every bundled encrypted resolver (DoH/DoT/DoQ over 443). */
private const val DnsProviderCatalogDefaultPort = 443

/** Coarse, advisory TSPU-risk hints used purely for UI annotation — never a network-scope input. */
const val DnsProviderTspuRiskHigh = "high"
const val DnsProviderTspuRiskLowMedium = "low-medium"
const val DnsProviderTspuRiskDomestic = "domestic"

/**
 * Machine-readable filtering / logging facts for a catalog entry, derived from the curated
 * human-readable filtering note. Absent fields default to the conservative interpretation
 * (everything false) so missing JSON keys never widen a privacy or filtering claim.
 */
@Serializable
data class DnsProviderCatalogFlags(
    @SerialName("no_log") val noLog: Boolean = false,
    @SerialName("no_filter") val noFilter: Boolean = false,
    @SerialName("dnssec") val dnssec: Boolean = false,
)

/**
 * Russian-jurisdiction advisory metadata. [jurisdictionRu] flags resolvers operated under RU
 * jurisdiction (RKN subject); [tspuRisk] is a coarse, advisory hint about TSPU throttling risk.
 *
 * This is APP CONFIG describing the chosen upstream resolver — it MUST NOT be fed into any
 * network scope-hash (see network-fingerprint-privacy.md).
 */
@Serializable
data class DnsProviderCatalogRf(
    @SerialName("jurisdiction_ru") val jurisdictionRu: Boolean = false,
    @SerialName("tspu_risk") val tspuRisk: String = "",
)

/**
 * One bundled DNS provider catalog entry. Snake_case wire keys map to camelCase Kotlin fields.
 * Unknown keys are ignored and absent keys fall back to defaults, so the asset can grow new
 * optional fields without breaking older parsers.
 */
@Serializable
data class DnsProviderCatalogEntry(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("protocols") val protocols: List<String> = emptyList(),
    @SerialName("doh_url") val dohUrl: String = "",
    @SerialName("dot_host") val dotHost: String = "",
    @SerialName("port") val port: Int = DnsProviderCatalogDefaultPort,
    @SerialName("bootstrap_ips") val bootstrapIps: List<String> = emptyList(),
    @SerialName("tls_server_name") val tlsServerName: String = "",
    @SerialName("flags") val flags: DnsProviderCatalogFlags = DnsProviderCatalogFlags(),
    @SerialName("rf") val rf: DnsProviderCatalogRf = DnsProviderCatalogRf(),
) {
    val supportsDoh: Boolean
        get() = protocols.any { it.equals(EncryptedDnsProtocolDoh, ignoreCase = true) }

    val supportsDot: Boolean
        get() = protocols.any { it.equals(EncryptedDnsProtocolDot, ignoreCase = true) }

    val supportsDoq: Boolean
        get() = protocols.any { it.equals(EncryptedDnsProtocolDoq, ignoreCase = true) }

    /** First bootstrap IP, used as the plain primary IP for the legacy [DnsProviderDefinition] shape. */
    val primaryIp: String
        get() = bootstrapIps.firstOrNull().orEmpty()
}

/** Top-level bundled catalog wrapper: `{ "schema": 1, "providers": [ ... ] }`. */
@Serializable
data class DnsProviderCatalog(
    @SerialName("schema") val schema: Int = DnsProviderCatalogSchemaVersion,
    @SerialName("providers") val providers: List<DnsProviderCatalogEntry> = emptyList(),
) {
    fun byId(id: String): DnsProviderCatalogEntry? = providers.firstOrNull { it.id == id }
}

private val dnsProviderCatalogJson =
    RipDpiJson

fun dnsProviderCatalogFromJson(payload: String): DnsProviderCatalog = dnsProviderCatalogJson.decodeFromString(payload)

fun DnsProviderCatalog.toJson(): String = dnsProviderCatalogJson.encodeToString(this)

/**
 * Embedded copy of the bundled `dns/dns-providers.json` asset. Kept as a compiled string constant
 * so the lower data modules (and their pure-JVM unit tests) can derive the canonical provider set
 * without an Android [android.content.Context]. The `:core:service` `DnsProviderCatalogLoader`
 * remains the runtime entry point that can shadow this default with an asset / filesDir override;
 * this constant is the offline fallback all layers agree on. Must stay byte-identical to the asset
 * — `DnsProviderCatalogAssetParityTest` enforces it.
 */
internal const val BundledDnsProviderCatalogJson: String =
    """
{
  "schema": 1,
  "providers": [
    {
      "id": "cloudflare",
      "name": "Cloudflare",
      "protocols": ["doh", "dot", "doq"],
      "doh_url": "https://cloudflare-dns.com/dns-query",
      "dot_host": "cloudflare-dns.com",
      "port": 443,
      "bootstrap_ips": ["1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"],
      "tls_server_name": "cloudflare-dns.com",
      "flags": { "no_log": false, "no_filter": true, "dnssec": true },
      "rf": { "jurisdiction_ru": false, "tspu_risk": "high" }
    },
    {
      "id": "cloudflare-malware",
      "name": "Cloudflare (malware)",
      "protocols": ["doh", "dot"],
      "doh_url": "https://security.cloudflare-dns.com/dns-query",
      "dot_host": "security.cloudflare-dns.com",
      "port": 443,
      "bootstrap_ips": ["1.1.1.2", "1.0.0.2", "2606:4700:4700::1112", "2606:4700:4700::1002"],
      "tls_server_name": "security.cloudflare-dns.com",
      "flags": { "no_log": false, "no_filter": false, "dnssec": false },
      "rf": { "jurisdiction_ru": false, "tspu_risk": "high" }
    },
    {
      "id": "google",
      "name": "Google",
      "protocols": ["doh", "dot"],
      "doh_url": "https://dns.google/dns-query",
      "dot_host": "dns.google",
      "port": 443,
      "bootstrap_ips": ["8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844"],
      "tls_server_name": "dns.google",
      "flags": { "no_log": false, "no_filter": true, "dnssec": false },
      "rf": { "jurisdiction_ru": false, "tspu_risk": "high" }
    },
    {
      "id": "quad9",
      "name": "Quad9",
      "protocols": ["doh", "dot", "doq"],
      "doh_url": "https://dns.quad9.net/dns-query",
      "dot_host": "dns.quad9.net",
      "port": 443,
      "bootstrap_ips": ["9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"],
      "tls_server_name": "dns.quad9.net",
      "flags": { "no_log": true, "no_filter": false, "dnssec": true },
      "rf": { "jurisdiction_ru": false, "tspu_risk": "low-medium" }
    },
    {
      "id": "quad9-unfiltered",
      "name": "Quad9 (unfiltered)",
      "protocols": ["doh", "dot"],
      "doh_url": "https://dns11.quad9.net/dns-query",
      "dot_host": "dns11.quad9.net",
      "port": 443,
      "bootstrap_ips": ["9.9.9.10", "149.112.112.10", "2620:fe::10", "2620:fe::fe:10"],
      "tls_server_name": "dns11.quad9.net",
      "flags": { "no_log": true, "no_filter": true, "dnssec": false },
      "rf": { "jurisdiction_ru": false, "tspu_risk": "low-medium" }
    },
    {
      "id": "adguard",
      "name": "AdGuard",
      "protocols": ["doh", "dot", "doq"],
      "doh_url": "https://dns.adguard-dns.com/dns-query",
      "dot_host": "dns.adguard-dns.com",
      "port": 443,
      "bootstrap_ips": ["94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"],
      "tls_server_name": "dns.adguard-dns.com",
      "flags": { "no_log": false, "no_filter": false, "dnssec": false },
      "rf": { "jurisdiction_ru": false, "tspu_risk": "low-medium" }
    },
    {
      "id": "adguard-unfiltered",
      "name": "AdGuard (unfiltered)",
      "protocols": ["doh", "dot", "doq"],
      "doh_url": "https://unfiltered.adguard-dns.com/dns-query",
      "dot_host": "unfiltered.adguard-dns.com",
      "port": 443,
      "bootstrap_ips": ["94.140.14.140", "94.140.14.141", "2a10:50c0::1:ff", "2a10:50c0::2:ff"],
      "tls_server_name": "unfiltered.adguard-dns.com",
      "flags": { "no_log": false, "no_filter": true, "dnssec": false },
      "rf": { "jurisdiction_ru": false, "tspu_risk": "low-medium" }
    },
    {
      "id": "mullvad",
      "name": "Mullvad",
      "protocols": ["doh", "dot", "doq"],
      "doh_url": "https://dns.mullvad.net/dns-query",
      "dot_host": "dns.mullvad.net",
      "port": 443,
      "bootstrap_ips": ["194.242.2.2", "2a07:e340::2"],
      "tls_server_name": "dns.mullvad.net",
      "flags": { "no_log": true, "no_filter": true, "dnssec": false },
      "rf": { "jurisdiction_ru": false, "tspu_risk": "low-medium" }
    },
    {
      "id": "mullvad-adblock",
      "name": "Mullvad (adblock)",
      "protocols": ["doh", "dot", "doq"],
      "doh_url": "https://adblock.dns.mullvad.net/dns-query",
      "dot_host": "adblock.dns.mullvad.net",
      "port": 443,
      "bootstrap_ips": ["194.242.2.3", "2a07:e340::3"],
      "tls_server_name": "adblock.dns.mullvad.net",
      "flags": { "no_log": true, "no_filter": false, "dnssec": false },
      "rf": { "jurisdiction_ru": false, "tspu_risk": "low-medium" }
    },
    {
      "id": "wikimedia",
      "name": "Wikimedia DNS",
      "protocols": ["doh", "dot"],
      "doh_url": "https://wikimedia-dns.org/dns-query",
      "dot_host": "wikimedia-dns.org",
      "port": 443,
      "bootstrap_ips": ["185.71.138.138", "2001:67c:930::1"],
      "tls_server_name": "wikimedia-dns.org",
      "flags": { "no_log": true, "no_filter": true, "dnssec": false },
      "rf": { "jurisdiction_ru": false, "tspu_risk": "low-medium" }
    },
    {
      "id": "dns-sb",
      "name": "DNS.SB",
      "protocols": ["doh", "dot"],
      "doh_url": "https://doh.dns.sb/dns-query",
      "dot_host": "doh.dns.sb",
      "port": 443,
      "bootstrap_ips": ["185.222.222.222", "45.11.45.11", "2a09::", "2a11::"],
      "tls_server_name": "doh.dns.sb",
      "flags": { "no_log": true, "no_filter": true, "dnssec": true },
      "rf": { "jurisdiction_ru": false, "tspu_risk": "low-medium" }
    },
    {
      "id": "cznic-odvr",
      "name": "CZ.NIC ODVR",
      "protocols": ["doh", "dot"],
      "doh_url": "https://odvr.nic.cz/dns-query",
      "dot_host": "odvr.nic.cz",
      "port": 443,
      "bootstrap_ips": ["193.17.47.1", "185.43.135.1", "2001:148f:ffff::1", "2001:148f:fffe::1"],
      "tls_server_name": "odvr.nic.cz",
      "flags": { "no_log": false, "no_filter": true, "dnssec": true },
      "rf": { "jurisdiction_ru": false, "tspu_risk": "low-medium" }
    },
    {
      "id": "digitale-ges",
      "name": "Digitale Gesellschaft",
      "protocols": ["doh", "dot"],
      "doh_url": "https://dns.digitale-gesellschaft.ch/dns-query",
      "dot_host": "dns.digitale-gesellschaft.ch",
      "port": 443,
      "bootstrap_ips": ["185.95.218.42", "185.95.218.43", "2a05:fc84::42", "2a05:fc84::43"],
      "tls_server_name": "dns.digitale-gesellschaft.ch",
      "flags": { "no_log": true, "no_filter": true, "dnssec": false },
      "rf": { "jurisdiction_ru": false, "tspu_risk": "low-medium" }
    },
    {
      "id": "controld-uncensored",
      "name": "ControlD (uncensored)",
      "protocols": ["doh", "dot"],
      "doh_url": "https://freedns.controld.com/p0",
      "dot_host": "p0.freedns.controld.com",
      "port": 443,
      "bootstrap_ips": ["76.76.2.0", "76.76.10.0", "2606:1a40::", "2606:1a40:1::"],
      "tls_server_name": "p0.freedns.controld.com",
      "flags": { "no_log": false, "no_filter": true, "dnssec": false },
      "rf": { "jurisdiction_ru": false, "tspu_risk": "low-medium" }
    },
    {
      "id": "yandex",
      "name": "Yandex",
      "protocols": ["doh", "dot"],
      "doh_url": "https://common.dot.dns.yandex.net/dns-query",
      "dot_host": "common.dot.dns.yandex.net",
      "port": 443,
      "bootstrap_ips": ["77.88.8.8", "77.88.8.1", "2a02:6b8::feed:0ff", "2a02:6b8:0:1::feed:0ff"],
      "tls_server_name": "common.dot.dns.yandex.net",
      "flags": { "no_log": false, "no_filter": true, "dnssec": false },
      "rf": { "jurisdiction_ru": true, "tspu_risk": "domestic" }
    }
  ]
}
"""

/**
 * Lazily-parsed singleton view of [BundledDnsProviderCatalogJson]. This is the canonical, offline
 * provider set shared by every data layer; runtime overrides (asset / filesDir) are layered on top
 * by `:core:service`'s loader, never here.
 */
val BundledDnsProviderCatalog: DnsProviderCatalog by lazy {
    dnsProviderCatalogFromJson(BundledDnsProviderCatalogJson)
}

/** Convenience: every bundled entry mapped onto the legacy [DnsProviderDefinition] shape. */
fun bundledDnsProviderDefinitions(): List<DnsProviderDefinition> =
    BundledDnsProviderCatalog.providers.map { it.toDnsProviderDefinition() }

/** Convenience: a single bundled entry mapped onto [DnsProviderDefinition], or null when absent. */
fun bundledDnsProviderDefinition(id: String): DnsProviderDefinition? =
    BundledDnsProviderCatalog.byId(id)?.toDnsProviderDefinition()

/**
 * Maps a catalog entry onto the legacy [DnsProviderDefinition] used by the encrypted-DNS settings
 * pipeline. Defaults to DoH when available, otherwise falls back to the first declared protocol.
 */
fun DnsProviderCatalogEntry.toDnsProviderDefinition(): DnsProviderDefinition {
    val resolvedProtocol =
        when {
            supportsDoh -> EncryptedDnsProtocolDoh
            supportsDot -> EncryptedDnsProtocolDot
            supportsDoq -> EncryptedDnsProtocolDoq
            else -> EncryptedDnsProtocolDoh
        }
    return DnsProviderDefinition(
        providerId = id,
        displayName = name,
        primaryIp = primaryIp,
        protocol = resolvedProtocol,
        host = dotHost,
        port = port,
        tlsServerName = tlsServerName.ifBlank { dotHost },
        bootstrapIps = bootstrapIps,
        dohUrl = dohUrl.takeIf { it.isNotBlank() },
    )
}
