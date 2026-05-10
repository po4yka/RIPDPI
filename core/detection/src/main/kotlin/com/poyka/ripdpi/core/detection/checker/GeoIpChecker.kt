package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection

object GeoIpChecker {
    internal data class GeoIpSnapshot(
        val ip: String,
        val country: String,
        val countryCode: String,
        val isp: String,
        val org: String,
        val asn: String,
        val isProxy: Boolean,
        val isHosting: Boolean,
        val providerName: String = "ipwho.is",
        val fetchedAtIndex: Int = 0,
        val providerCount: Int = 1,
        val conflictingProviders: List<String> = emptyList(),
    )

    internal data class GeoIpProvider(
        val name: String,
        val urlForIp: (String) -> String,
        val parse: (JSONObject, Int) -> GeoIpSnapshot,
    )

    internal interface GeoIpProviderClient {
        suspend fun fetchSeedIp(): String

        suspend fun fetchProvider(
            provider: GeoIpProvider,
            ip: String,
        ): GeoIpSnapshot
    }

    internal suspend fun check(
        dispatchers: AppCoroutineDispatchers,
        client: GeoIpProviderClient = DefaultGeoIpProviderClient(),
    ): CategoryResult =
        withContext(dispatchers.io) {
            try {
                val ip = client.fetchSeedIp()
                val snapshots = fetchProviderSnapshots(client, ip)
                if (snapshots.isEmpty()) {
                    return@withContext errorResult("Failed to fetch GeoIP data from all providers")
                }
                evaluateConsensus(snapshots)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                errorResult("Failed to fetch GeoIP data: ${e.message}")
            }
        }

    private suspend fun fetchProviderSnapshots(
        client: GeoIpProviderClient,
        ip: String,
    ): List<GeoIpSnapshot> =
        coroutineScope {
            PROVIDERS
                .mapIndexed { index, provider ->
                    async {
                        try {
                            client.fetchProvider(provider, ip).copy(fetchedAtIndex = index)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                }.awaitAll()
                .filterNotNull()
        }

    private fun fetchJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = GEO_IP_TIMEOUT_MS.toInt()
        connection.readTimeout = GEO_IP_TIMEOUT_MS.toInt()
        connection.requestMethod = "GET"
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw IOException("GeoIP HTTP $statusCode")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun evaluate(json: JSONObject): CategoryResult = evaluate(snapshotFrom(json))

    internal fun evaluateConsensus(snapshots: List<GeoIpSnapshot>): CategoryResult = evaluate(mergeSnapshots(snapshots))

    internal fun evaluate(snapshot: GeoIpSnapshot): CategoryResult {
        val findings = mutableListOf<Finding>()
        val evidence = mutableListOf<EvidenceItem>()

        findings.add(Finding("IP: ${snapshot.ip}"))
        findings.add(Finding("Provider count: ${snapshot.providerCount}"))
        if (snapshot.conflictingProviders.isNotEmpty()) {
            findings.add(Finding("Conflicting providers: ${snapshot.conflictingProviders.joinToString()}"))
        }
        findings.add(Finding("Country: ${snapshot.country} (${snapshot.countryCode})"))
        findings.add(Finding("ISP: ${snapshot.isp}"))
        findings.add(Finding("Org: ${snapshot.org}"))
        findings.add(Finding("ASN: ${snapshot.asn}"))

        val foreignIp = snapshot.countryCode.isNotEmpty() && snapshot.countryCode != "RU"
        val needsReview = foreignIp && !snapshot.isHosting && !snapshot.isProxy
        findings.add(
            Finding(
                description = "IP outside Russia: ${if (foreignIp) "yes (${snapshot.countryCode})" else "no"}",
                needsReview = needsReview,
                source = EvidenceSource.GEO_IP,
                confidence = needsReview.takeIf { it }?.let { EvidenceConfidence.LOW },
            ),
        )
        addGeoFinding(
            findings = findings,
            evidence = evidence,
            description = "IP belongs to hosting provider: ${if (snapshot.isHosting) "yes" else "no"}",
            detected = snapshot.isHosting,
        )
        addGeoFinding(
            findings = findings,
            evidence = evidence,
            description = "IP in known proxy/VPN database: ${if (snapshot.isProxy) "yes" else "no"}",
            detected = snapshot.isProxy,
        )

        return CategoryResult(
            name = "GeoIP",
            detected = snapshot.isHosting || snapshot.isProxy,
            findings = findings,
            needsReview = needsReview,
            evidence = evidence,
        )
    }

    private fun mergeSnapshots(snapshots: List<GeoIpSnapshot>): GeoIpSnapshot {
        require(snapshots.isNotEmpty()) { "At least one GeoIP provider response is required" }

        val proxyMajority = snapshots.count { it.isProxy } >= PROVIDER_MAJORITY
        val hostingMajority = snapshots.count { it.isHosting } >= PROVIDER_MAJORITY
        val countryCode = majorityString(snapshots, GeoIpSnapshot::countryCode)
        val countrySnapshot =
            snapshots
                .filter { it.countryCode == countryCode }
                .maxByOrNull { it.fetchedAtIndex }
                ?: snapshots.maxBy { it.fetchedAtIndex }
        val asn = majorityString(snapshots, GeoIpSnapshot::asn)
        val asnSnapshot =
            snapshots
                .filter { it.asn == asn }
                .maxByOrNull { it.fetchedAtIndex }
                ?: countrySnapshot
        val conflictingProviders =
            snapshots
                .filter { it.isProxy != proxyMajority || it.isHosting != hostingMajority }
                .map { it.providerName }

        return GeoIpSnapshot(
            ip = snapshots.last().ip,
            country = countrySnapshot.country,
            countryCode = countrySnapshot.countryCode,
            isp = asnSnapshot.isp,
            org = asnSnapshot.org,
            asn = asnSnapshot.asn,
            isProxy = proxyMajority,
            isHosting = hostingMajority,
            providerName = "consensus",
            fetchedAtIndex = snapshots.maxOf { it.fetchedAtIndex },
            providerCount = snapshots.size,
            conflictingProviders = conflictingProviders,
        )
    }

    private fun majorityString(
        snapshots: List<GeoIpSnapshot>,
        selector: (GeoIpSnapshot) -> String,
    ): String {
        val groups = snapshots.groupBy(selector)
        val maxCount = groups.values.maxOf { it.size }
        val tiedValues = groups.filterValues { it.size == maxCount }.keys
        return snapshots
            .filter { selector(it) in tiedValues }
            .maxBy { it.fetchedAtIndex }
            .let(selector)
    }

    private fun errorResult(message: String): CategoryResult =
        CategoryResult(
            name = "GeoIP",
            detected = false,
            findings = listOf(Finding(message)),
        )

    private fun addGeoFinding(
        findings: MutableList<Finding>,
        evidence: MutableList<EvidenceItem>,
        description: String,
        detected: Boolean,
    ) {
        findings.add(
            Finding(
                description = description,
                detected = detected,
                source = EvidenceSource.GEO_IP,
                confidence = detected.takeIf { it }?.let { EvidenceConfidence.MEDIUM },
            ),
        )
        if (detected) {
            evidence.add(
                EvidenceItem(
                    source = EvidenceSource.GEO_IP,
                    detected = true,
                    confidence = EvidenceConfidence.MEDIUM,
                    description = description,
                ),
            )
        }
    }

    private fun snapshotFrom(
        json: JSONObject,
        providerName: String = "ipwho.is",
        fetchedAtIndex: Int = 0,
    ): GeoIpSnapshot {
        val connection = json.optJSONObject("connection")
        val security = json.optJSONObject("security")
        val location = json.optJSONObject("location")
        val asnObject = json.optJSONObject("asn")
        val company = json.optJSONObject("company")
        val datacenter = json.optJSONObject("datacenter")
        val hosting = json.optJSONObject("hosting")
        val privacy = json.optJSONObject("privacy")
        val geo = json.optJSONObject("geo")
        val network = json.optJSONObject("network")
        val ispObject = json.optJSONObject("isp")
        val risk = json.optJSONObject("risk")
        val legacyAsn = json.optString("as")
        val connectionAsn = connection?.opt("asn")?.toString().orEmpty()
        val networkRadar =
            network
                ?.optString("radar")
                .orEmpty()
                .trim()
                .lowercase()
        val usageType =
            security
                ?.optString("usage_type")
                .orEmpty()
                .trim()
                .uppercase()
        val threatLists = security?.optJSONArray("threat_lists")
        return GeoIpSnapshot(
            ip = json.firstString("query", "ip").ifBlank { "N/A" },
            country =
                firstMeaningful(
                    json.firstString("country", "country_name"),
                    location?.optString("country"),
                    geo?.optString("country"),
                    default = "N/A",
                ),
            countryCode =
                firstMeaningful(
                    json.firstString("countryCode", "country_code", "country_code2"),
                    location?.optString("country_code"),
                    geo?.optString("country_code"),
                    default = "",
                ),
            isp =
                firstMeaningful(
                    connection?.optString("isp"),
                    company?.optString("name"),
                    asnObject?.optString("org"),
                    asnObject?.optString("name"),
                    datacenter?.optString("datacenter"),
                    asnObject?.optString("descr"),
                    hosting?.optString("provider"),
                    ispObject?.optString("isp"),
                    ispObject?.optString("org"),
                    network?.optString("isp"),
                    network?.optString("org"),
                    json.firstString("isp", "company", "org"),
                    default = "N/A",
                ),
            org =
                firstMeaningful(
                    connection?.optString("org"),
                    datacenter?.optString("datacenter"),
                    company?.optString("name"),
                    asnObject?.optString("org"),
                    asnObject?.optString("name"),
                    asnObject?.optString("descr"),
                    hosting?.optString("provider"),
                    ispObject?.optString("org"),
                    ispObject?.optString("isp"),
                    network?.optString("org"),
                    network?.optString("isp"),
                    json.firstString("org", "company"),
                    default = "N/A",
                ),
            asn =
                firstMeaningful(
                    connectionAsn,
                    asnObject?.opt("asn")?.toString(),
                    ispObject?.opt("asn")?.toString(),
                    network?.opt("asn")?.toString(),
                    json.opt("asn")?.toString(),
                    legacyAsn,
                    default = "N/A",
                ),
            isProxy =
                json.optBoolean("proxy", false) ||
                    json.optBoolean("is_proxy", false) ||
                    json.optBoolean("is_vpn", false) ||
                    json.optBoolean("is_tor", false) ||
                    json.optBoolean("vpn", false) ||
                    security?.optBoolean("proxy", false) == true ||
                    security?.optBoolean("is_proxy", false) == true ||
                    security?.optBoolean("vpn", false) == true ||
                    security?.optBoolean("is_vpn", false) == true ||
                    security?.optBoolean("tor", false) == true ||
                    security?.optBoolean("is_tor", false) == true ||
                    privacy?.optBoolean("proxy", false) == true ||
                    privacy?.optBoolean("vpn", false) == true ||
                    privacy?.optBoolean("tor", false) == true ||
                    privacy?.optBoolean("is_proxy", false) == true ||
                    privacy?.optBoolean("is_vpn", false) == true ||
                    privacy?.optBoolean("is_tor", false) == true ||
                    risk?.optBoolean("is_proxy", false) == true ||
                    risk?.optBoolean("is_vpn", false) == true ||
                    risk?.optBoolean("is_tor", false) == true ||
                    threatLists.containsAny("proxy", "proxies", "vpn", "tor") ||
                    networkRadar == "vpn" ||
                    networkRadar == "proxy" ||
                    networkRadar == "tor",
            isHosting =
                json.optBoolean("hosting", false) ||
                    json.optBoolean("is_datacenter", false) ||
                    json.optBoolean("datacenter", false) ||
                    security?.optBoolean("hosting", false) == true ||
                    security?.optBoolean("is_datacenter", false) == true ||
                    privacy?.optBoolean("hosting", false) == true ||
                    privacy?.optBoolean("is_hosting", false) == true ||
                    risk?.optBoolean("is_datacenter", false) == true ||
                    usageType == "DCH" ||
                    networkRadar == "datacenter",
            providerName = providerName,
            fetchedAtIndex = fetchedAtIndex,
        )
    }

    private fun JSONObject.firstString(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> optString(key).takeIf(String::isNotBlank) }.orEmpty()

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
        val normalizedCandidates = candidates.map { it.lowercase() }.toSet()
        for (index in 0 until length()) {
            if (optString(index).trim().lowercase() in normalizedCandidates) return true
        }
        return false
    }

    private fun jsonResponseSuccessful(json: JSONObject): Boolean =
        when {
            json.has("success") -> json.optBoolean("success", false)
            json.optString("status") == "success" -> true
            json.has("error") -> false
            else -> false
        }

    private class DefaultGeoIpProviderClient : GeoIpProviderClient {
        override suspend fun fetchSeedIp(): String {
            val json = fetchJson(SEED_URL)
            ensureProviderSuccess(json)
            return snapshotFrom(json).ip
        }

        override suspend fun fetchProvider(
            provider: GeoIpProvider,
            ip: String,
        ): GeoIpSnapshot {
            val json = fetchJson(provider.urlForIp(ip))
            ensureProviderSuccess(json)
            return provider.parse(json, 0)
        }

        private fun ensureProviderSuccess(json: JSONObject) {
            if (json.has("success") || json.has("status") || json.has("error")) {
                if (!jsonResponseSuccessful(json)) {
                    val message = json.optString("message").ifBlank { json.optString("error") }
                    throw IOException(message.ifBlank { "GeoIP API returned an error" })
                }
            }
        }
    }

    private const val GEO_IP_TIMEOUT_MS = 10_000L
    private const val PROVIDER_MAJORITY = 3
    private const val SEED_URL =
        "https://ipwho.is/?fields=ip,success,message,country,country_code,connection,security"
    private val PROVIDERS =
        listOf(
            GeoIpProvider(
                name = "ipapi.is",
                urlForIp = { ip -> "https://api.ipapi.is/?q=$ip" },
                parse = { json, index -> snapshotFrom(json, "ipapi.is", index) },
            ),
            GeoIpProvider(
                name = "iplocate.io",
                urlForIp = { ip -> "https://www.iplocate.io/api/lookup/$ip" },
                parse = { json, index -> snapshotFrom(json, "iplocate.io", index) },
            ),
            GeoIpProvider(
                name = "ipquery.io",
                urlForIp = { ip -> "https://api.ipquery.io/$ip" },
                parse = { json, index -> snapshotFrom(json, "ipquery.io", index) },
            ),
            GeoIpProvider(
                name = "iplookup.it",
                urlForIp = { ip -> "https://www.iplookup.it/ip/$ip" },
                parse = { json, index -> snapshotFrom(json, "iplookup.it", index) },
            ),
            GeoIpProvider(
                name = "ipbot.com",
                urlForIp = { ip -> "https://api.ipbot.com/$ip" },
                parse = { json, index -> snapshotFrom(json, "ipbot.com", index) },
            ),
        )
}
