package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
    )

    private const val API_URL =
        "http://ip-api.com/json/?fields=status,country,countryCode,isp,org,as,proxy,hosting,query"

    suspend fun check(): CategoryResult =
        withContext(Dispatchers.IO) {
            try {
                val json = fetchJson()
                if (json.optString("status") != "success") {
                    return@withContext errorResult("ip-api returned an error")
                }
                evaluate(json)
            } catch (e: Exception) {
                errorResult("Failed to fetch GeoIP data: ${e.message}")
            }
        }

    private fun fetchJson(): JSONObject {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            val body = connection.inputStream.bufferedReader().readText()
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun evaluate(json: JSONObject): CategoryResult =
        evaluate(
            GeoIpSnapshot(
                ip = json.optString("query", "N/A"),
                country = json.optString("country", "N/A"),
                countryCode = json.optString("countryCode", ""),
                isp = json.optString("isp", "N/A"),
                org = json.optString("org", "N/A"),
                asn = json.optString("as", "N/A"),
                isProxy = json.optBoolean("proxy", false),
                isHosting = json.optBoolean("hosting", false),
            ),
        )

    internal fun evaluate(snapshot: GeoIpSnapshot): CategoryResult {
        val findings = mutableListOf<Finding>()
        val evidence = mutableListOf<EvidenceItem>()

        findings.add(Finding("IP: ${snapshot.ip}"))
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
}
