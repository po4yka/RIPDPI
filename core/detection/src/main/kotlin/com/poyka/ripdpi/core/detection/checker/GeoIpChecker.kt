package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URL
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
    )

    suspend fun check(dispatchers: AppCoroutineDispatchers): CategoryResult =
        withContext(dispatchers.io) {
            try {
                val json = fetchJson()
                if (!jsonResponseSuccessful(json)) {
                    val message = json.optString("message")
                    val errorMessage =
                        message.takeIf(String::isNotBlank)
                            ?: "GeoIP API returned an error"
                    return@withContext errorResult(errorMessage)
                }
                evaluate(json)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                errorResult("Failed to fetch GeoIP data: ${e.message}")
            }
        }

    private fun fetchJson(): JSONObject {
        val connection = URL(API_URL).openConnection() as HttpsURLConnection
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

    private fun snapshotFrom(json: JSONObject): GeoIpSnapshot {
        val connection = json.optJSONObject("connection")
        val security = json.optJSONObject("security")
        val legacyAsn = json.optString("as")
        val connectionAsn = connection?.opt("asn")?.toString().orEmpty()
        return GeoIpSnapshot(
            ip = json.optString("query").ifBlank { json.optString("ip", "N/A") },
            country = json.optString("country", "N/A"),
            countryCode = json.optString("countryCode").ifBlank { json.optString("country_code") },
            isp = connection?.optString("isp").orEmpty().ifBlank { json.optString("isp", "N/A") },
            org = connection?.optString("org").orEmpty().ifBlank { json.optString("org", "N/A") },
            asn = connectionAsn.ifBlank { legacyAsn }.ifBlank { "N/A" },
            isProxy =
                json.optBoolean("proxy", false) ||
                    security?.optBoolean("proxy", false) == true ||
                    security?.optBoolean("vpn", false) == true ||
                    security?.optBoolean("tor", false) == true,
            isHosting =
                json.optBoolean("hosting", false) ||
                    security?.optBoolean("hosting", false) == true,
        )
    }

    private fun jsonResponseSuccessful(json: JSONObject): Boolean =
        when {
            json.has("success") -> json.optBoolean("success", false)
            json.optString("status") == "success" -> true
            else -> false
        }

    private const val GEO_IP_TIMEOUT_MS = 10_000L
    private const val API_URL =
        "https://ipwho.is/?fields=ip,success,message,country,country_code,connection,security"
}
