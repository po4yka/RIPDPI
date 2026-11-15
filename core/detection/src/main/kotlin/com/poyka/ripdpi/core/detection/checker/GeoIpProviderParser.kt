package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.checker.GeoIpChecker.GeoIpSnapshot
import com.poyka.ripdpi.core.detection.probe.publicIpOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

internal object GeoIpProviderParser {
    fun parse(
        json: JSONObject,
        providerName: String = "ipwho.is",
        fetchedAtIndex: Int = 0,
    ): GeoIpSnapshot {
        ensureSuccessful(json)
        val connection = json.optJSONObject("connection")
        val location = json.optJSONObject("location")
        val asnObject = json.optJSONObject("asn")
        val company = json.optJSONObject("company")
        val datacenter = json.optJSONObject("datacenter")
        val hosting = json.optJSONObject("hosting")
        val geo = json.optJSONObject("geo")
        val network = json.optJSONObject("network")
        val ispObject = json.optJSONObject("isp")
        val legacyAsn = json.text("as")
        val connectionAsn = connection.asnScalar()
        return validate(
            GeoIpSnapshot(
                ip = identity(json),
                country =
                    firstMeaningful(
                        json.text("country", "country_name"),
                        location?.text("country"),
                        geo?.text("country"),
                        default = "N/A",
                    ),
                countryCode =
                    firstMeaningful(
                        json.text("countryCode", "country_code", "country_code2"),
                        location?.text("country_code"),
                        geo?.text("country_code"),
                        default = "",
                    ),
                isp =
                    firstMeaningful(
                        connection?.text("isp"),
                        company?.text("name"),
                        asnObject?.text("org", "name"),
                        datacenter?.text("datacenter"),
                        asnObject?.text("descr"),
                        hosting?.text("provider"),
                        ispObject?.text("isp", "org"),
                        network?.text("isp", "org"),
                        json.text("isp", "company", "org"),
                        default = "N/A",
                    ),
                org =
                    firstMeaningful(
                        connection?.text("org"),
                        datacenter?.text("datacenter"),
                        company?.text("name"),
                        asnObject?.text("org", "name"),
                        asnObject?.text("descr"),
                        hosting?.text("provider"),
                        ispObject?.text("org", "isp"),
                        network?.text("org", "isp"),
                        json.text("org", "company"),
                        default = "N/A",
                    ),
                asn =
                    firstMeaningful(
                        connectionAsn,
                        asnObject.asnScalar(),
                        ispObject.asnScalar(),
                        network.asnScalar(),
                        json.asnScalar(),
                        legacyAsn,
                        default = "N/A",
                    ),
                isProxy = proxyTrait(json),
                isHosting = hostingTrait(json),
                providerName = providerName,
                fetchedAtIndex = fetchedAtIndex,
            ),
        )
    }

    private fun proxyTrait(json: JSONObject): Boolean? {
        val security = json.optJSONObject("security")
        val privacy = json.optJSONObject("privacy")
        val risk = json.optJSONObject("risk")
        val classification = json.optJSONObject("classification")
        return listOf(
            json.proxyFlags("is_proxy", "is_vpn", "is_tor"),
            security.proxyFlags("proxy", "vpn", "tor"),
            security.proxyFlags("is_proxy", "is_vpn", "is_tor"),
            privacy.proxyFlags("proxy", "vpn", "tor"),
            privacy.proxyFlags("is_proxy", "is_vpn", "is_tor"),
            risk.proxyFlags("is_proxy", "is_vpn", "is_tor"),
            classification.boolean("is_proxy"),
            classification.proxyFlags("is_proxy", "is_vpn", "is_tor"),
            json.boolean("proxy").takeIf { json.text("query").isNotBlank() && json.text("status") == "success" },
            true.takeIf { json.boolean("proxy") == true || json.boolean("vpn") == true },
            true.takeIf { security?.optJSONArray("threat_lists").containsAny("proxy", "proxies", "vpn", "tor") },
            true.takeIf {
                json
                    .optJSONObject(
                        "network",
                    ).text("radar")
                    .lowercase(Locale.ROOT) in setOf("proxy", "vpn", "tor")
            },
        ).combineKnown()
    }

    private fun hostingTrait(json: JSONObject): Boolean? {
        val security = json.optJSONObject("security")
        val privacy = json.optJSONObject("privacy")
        val risk = json.optJSONObject("risk")
        val classification = json.optJSONObject("classification")
        return listOf(
            json.boolean("hosting"),
            json.boolean("is_datacenter"),
            json.boolean("datacenter"),
            security.boolean("hosting"),
            security.boolean("is_datacenter"),
            privacy.boolean("hosting"),
            privacy.boolean("is_hosting"),
            risk.boolean("is_datacenter"),
            classification.boolean("is_datacenter"),
            true.takeIf {
                security.text("usage_type").uppercase(Locale.ROOT) == "DCH" ||
                    json.optJSONObject("network").text("radar").lowercase(Locale.ROOT) == "datacenter"
            },
        ).combineKnown()
    }

    fun seedIp(json: JSONObject): String {
        ensureSuccessful(json)
        if (!json.has("ip")) throw IOException("GeoIP seed has no public numeric IP")
        return identity(json)
    }

    private fun identity(json: JSONObject): String {
        val addresses =
            listOf("query", "ip").filter(json::has).map { key ->
                (json.opt(key) as? String)?.let(::publicIpOrNull)
            }
        return addresses.distinct().singleOrNull()
            ?: throw IOException("GeoIP response has missing, malformed, or conflicting identity fields")
    }

    fun validate(
        snapshot: GeoIpSnapshot,
        expectedIp: String? = null,
    ): GeoIpSnapshot {
        val ip = publicIpOrNull(snapshot.ip) ?: throw IOException("GeoIP response has no public numeric IP")
        if (expectedIp != null &&
            ip != publicIpOrNull(expectedIp)
        ) {
            throw IOException("GeoIP response IP differs from requested IP")
        }
        return snapshot.copy(
            ip = ip,
            countryCode =
                snapshot.countryCode
                    .uppercase(Locale.ROOT)
                    .takeIf { COUNTRY_CODE.matches(it) }
                    .orEmpty(),
            asn = normalizeAsn(snapshot.asn).ifBlank { "N/A" },
        )
    }

    private fun ensureSuccessful(json: JSONObject) {
        val error = json.opt("error")
        val unsuccessful = json.has("success") && json.opt("success") != true
        val invalidStatus = json.has("status") && json.opt("status") != "success"
        val hasError = error != null && error != JSONObject.NULL && error != false
        if (unsuccessful || invalidStatus || hasError) throw IOException("GeoIP API returned an error")
    }

    private fun JSONObject?.text(vararg keys: String): String =
        keys
            .firstNotNullOfOrNull { key ->
                (this?.opt(key) as? String)?.trim()?.takeIf { it.isNotBlank() && it !in setOf("N/A", "null") }
            }.orEmpty()

    private val COUNTRY_CODE = Regex("[A-Z]{2}")
}

private fun firstMeaningful(
    vararg candidates: String?,
    default: String,
): String =
    candidates
        .firstOrNull { !it.isNullOrBlank() && !it.equals("N/A", ignoreCase = true) }
        ?.trim()
        ?: default

private fun org.json.JSONArray?.containsAny(vararg candidates: String): Boolean {
    if (this == null) return false
    val normalizedCandidates = candidates.map { it.lowercase(Locale.ROOT) }.toSet()
    return (0 until length()).any { index ->
        (opt(index) as? String)?.trim()?.lowercase(Locale.ROOT) in normalizedCandidates
    }
}

private fun JSONObject?.boolean(key: String): Boolean? = this?.opt(key) as? Boolean

private fun JSONObject?.proxyFlags(vararg keys: String): Boolean? {
    val values = keys.map { boolean(it) }
    return when {
        values.any { it == true } -> true
        values.all { it == false } -> false
        else -> null
    }
}

private fun List<Boolean?>.combineKnown(): Boolean? =
    when {
        any { it == true } -> true
        any { it == false } -> false
        else -> null
    }

private fun JSONObject?.asnScalar(): String {
    val value = this?.opt("asn")
    return normalizeAsn(if (value is String || value is Number) value.toString() else "")
}

private fun normalizeAsn(value: String): String {
    val number =
        ASN
            .find(value.trim())
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?.takeIf { it in 1..MaxAsn }
    return number?.let { "AS$it" }.orEmpty()
}

private val ASN = Regex("(?i)^(?:AS)?([0-9]+)(?:\\s.*)?$")

private const val MaxAsn = 4_294_967_295L
