@file:Suppress(
    "CyclomaticComplexMethod",
    "TooManyFunctions",
    "TooGenericExceptionCaught",
    "SwallowedException",
    "ReturnCount",
    "MagicNumber",
    "LongMethod",
)

package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckStatus
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.consensus.IpAsnInfo
import com.poyka.ripdpi.core.detection.probe.publicIpOrNull
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.diagnostics.DetectionResolverConfig
import com.poyka.ripdpi.data.diagnostics.DetectionResolverNetworkStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CancellationException

object GeoIpChecker {
    internal data class GeoIpSnapshot(
        val ip: String,
        val country: String,
        val countryCode: String,
        val isp: String,
        val org: String,
        val asn: String,
        val isProxy: Boolean?,
        val isHosting: Boolean?,
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
        resolverConfig: DetectionResolverConfig = DetectionResolverConfig(),
        client: GeoIpProviderClient = DefaultGeoIpProviderClient(resolverConfig),
    ): CategoryResult =
        withContext(dispatchers.io) {
            try {
                val ip =
                    publicIpOrNull(client.fetchSeedIp()) ?: throw IOException("GeoIP seed has no public numeric IP")
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

    internal suspend fun resolveAsnInfo(
        ip: String,
        resolverConfig: DetectionResolverConfig = DetectionResolverConfig(),
        client: GeoIpProviderClient = DefaultGeoIpProviderClient(resolverConfig),
    ): IpAsnInfo? =
        withContext(Dispatchers.IO) {
            try {
                val queryIp = publicIpOrNull(ip) ?: return@withContext null
                val snapshots = fetchProviderSnapshots(client, queryIp)
                if (snapshots.isEmpty()) return@withContext null
                val snapshot = mergeSnapshots(snapshots)
                IpAsnInfo(
                    ip = queryIp,
                    asn = snapshot.asn.takeUnless { it == "N/A" },
                    countryCode = snapshot.countryCode.takeIf(String::isNotBlank),
                    org = snapshot.org.takeUnless { it == "N/A" },
                )
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                null
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
                            GeoIpProviderParser
                                .validate(client.fetchProvider(provider, ip), ip)
                                .copy(providerName = provider.name, fetchedAtIndex = index)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                }.awaitAll()
                .filterNotNull()
        }

    private fun fetchJson(
        url: String,
        resolverConfig: DetectionResolverConfig,
    ): JSONObject {
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
        val response =
            DEFAULT_NETWORK_STACK.execute(
                request = request,
                config =
                    resolverConfig.copy(
                        connectTimeoutMillis = GEO_IP_TIMEOUT_MS,
                        readTimeoutMillis = GEO_IP_TIMEOUT_MS,
                        callTimeoutMillis = GEO_IP_TIMEOUT_MS,
                    ),
            )
        if (response.code !in 200..299) {
            throw IOException("GeoIP HTTP ${response.code}")
        }
        return JSONObject(response.body)
    }

    internal fun evaluate(json: JSONObject): CategoryResult = evaluate(GeoIpProviderParser.parse(json))

    internal fun evaluateConsensus(snapshots: List<GeoIpSnapshot>): CategoryResult {
        val validSnapshots = snapshots.map { GeoIpProviderParser.validate(it) }.distinctBy { it.providerName }
        if (validSnapshots.isEmpty() || validSnapshots.map { it.ip }.distinct().size != 1) {
            return errorResult("GeoIP provider responses do not identify one public IP")
        }
        val result = evaluate(mergeSnapshots(validSnapshots))
        if (validSnapshots.size >= MIN_PROVIDER_COUNT) return result
        return result.copy(
            findings =
                result.findings +
                    Finding(
                        description = "GeoIP consensus insufficient: only ${validSnapshots.size} providers responded",
                        source = EvidenceSource.GEO_IP,
                        confidence = EvidenceConfidence.LOW,
                    ),
        )
    }

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
        val needsReview = foreignIp && snapshot.isHosting != true && snapshot.isProxy != true
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
            description = "IP belongs to hosting provider: ${snapshot.isHosting.label()}",
            detected = snapshot.isHosting,
        )
        addGeoFinding(
            findings = findings,
            evidence = evidence,
            description = "IP in known proxy/VPN database: ${snapshot.isProxy.label()}",
            detected = snapshot.isProxy,
        )

        return CategoryResult(
            name = "GeoIP",
            detected = snapshot.isHosting == true || snapshot.isProxy == true,
            status =
                if (snapshot.isHosting == null ||
                    snapshot.isProxy == null
                ) {
                    DetectionCheckStatus.INCONCLUSIVE
                } else {
                    DetectionCheckStatus.COMPLETE
                },
            findings = findings,
            needsReview = needsReview,
            evidence = evidence,
        )
    }

    private fun mergeSnapshots(snapshots: List<GeoIpSnapshot>): GeoIpSnapshot {
        require(snapshots.isNotEmpty()) { "At least one GeoIP provider response is required" }

        val proxyMajority = snapshots.mapNotNull { it.isProxy }.traitConsensus()
        val hostingMajority =
            snapshots.mapNotNull { it.isHosting }.traitConsensus()
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
                .filter {
                    (proxyMajority != null && it.isProxy != null && it.isProxy != proxyMajority) ||
                        (hostingMajority != null && it.isHosting != null && it.isHosting != hostingMajority)
                }.map { it.providerName }

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
            status = DetectionCheckStatus.ERROR,
            findings = listOf(Finding(message)),
        )

    private fun addGeoFinding(
        findings: MutableList<Finding>,
        evidence: MutableList<EvidenceItem>,
        description: String,
        detected: Boolean?,
    ) {
        findings.add(
            Finding(
                description = description,
                detected = detected == true,
                source = EvidenceSource.GEO_IP,
                confidence = detected?.takeIf { it }?.let { EvidenceConfidence.MEDIUM },
            ),
        )
        if (detected == true) {
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

    private fun List<Boolean>.traitConsensus(): Boolean? {
        if (size < MIN_PROVIDER_COUNT) return null
        val positives = count { it }
        return when {
            positives > size / 2 -> true
            size - positives > size / 2 -> false
            else -> null
        }
    }

    private fun Boolean?.label(): String =
        when (this) {
            true -> "yes"
            false -> "no"
            null -> "unknown"
        }

    private class DefaultGeoIpProviderClient(
        private val resolverConfig: DetectionResolverConfig,
    ) : GeoIpProviderClient {
        override suspend fun fetchSeedIp(): String {
            val json = fetchJson(SEED_URL, resolverConfig)
            return GeoIpProviderParser.seedIp(json)
        }

        override suspend fun fetchProvider(
            provider: GeoIpProvider,
            ip: String,
        ): GeoIpSnapshot {
            val json = fetchJson(provider.urlForIp(ip), resolverConfig)
            return provider.parse(json, 0)
        }
    }

    private const val GEO_IP_TIMEOUT_MS = 10_000L
    private const val MIN_PROVIDER_COUNT = 3
    private val DEFAULT_NETWORK_STACK = DetectionResolverNetworkStack()
    private const val SEED_URL =
        "https://ipwho.is/?fields=ip,success,message,country,country_code,connection,security"
    private val PROVIDERS =
        listOf(
            GeoIpProvider(
                name = "ipapi.is",
                urlForIp = { ip -> "https://api.ipapi.is/?q=$ip" },
                parse = { json, index -> GeoIpProviderParser.parse(json, "ipapi.is", index) },
            ),
            GeoIpProvider(
                name = "iplocate.io",
                urlForIp = { ip -> "https://www.iplocate.io/api/lookup/$ip" },
                parse = { json, index -> GeoIpProviderParser.parse(json, "iplocate.io", index) },
            ),
            GeoIpProvider(
                name = "ipquery.io",
                urlForIp = { ip -> "https://api.ipquery.io/$ip" },
                parse = { json, index -> GeoIpProviderParser.parse(json, "ipquery.io", index) },
            ),
            GeoIpProvider(
                name = "iplookup.it",
                urlForIp = { ip -> "https://www.iplookup.it/ip/$ip" },
                parse = { json, index -> GeoIpProviderParser.parse(json, "iplookup.it", index) },
            ),
            GeoIpProvider(
                name = "ipbot.com",
                urlForIp = { ip -> "https://api.ipbot.com/v1/ip/$ip?fields=ip,location,network,classification" },
                parse = { json, index -> GeoIpProviderParser.parse(json, "ipbot.com", index) },
            ),
        )
}
