package com.poyka.ripdpi.diagnostics.rkn

import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SelfInfoResult(
    val ip: String,
    val asn: String?,
    val org: String?,
    val city: String?,
    val region: String?,
    val country: String?,
    val source: String,
)

data class SelfInfoConfig(
    val primaryUrl: String = "https://ipinfo.io/json",
    val fallbackUrl: String = "https://ifconfig.co/json",
    val timeoutMs: Long = 5_000,
)

data class SelfInfoOrg(
    val asn: String?,
    val org: String?,
)

class SelfInfoFetcher(
    private val clientBuilder: (OkHttpClient.Builder.() -> Unit) -> OkHttpClient =
        { configure -> OkHttpClient.Builder().apply(configure).build() },
    private val config: SelfInfoConfig = SelfInfoConfig(),
) {
    private val json =
        RipDpiJson

    suspend fun fetch(
        enabled: Boolean,
        privacyModeEnabled: Boolean,
    ): SelfInfoResult? {
        if (!enabled || privacyModeEnabled) {
            return null
        }
        return withContext(Dispatchers.IO) {
            fetchEndpoint(config.primaryUrl, "ipinfo.io") ?: fetchEndpoint(config.fallbackUrl, "ifconfig.co")
        }
    }

    private fun fetchEndpoint(
        url: String,
        source: String,
    ): SelfInfoResult? {
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .build()
        return runCatching {
            clientBuilder {
                connectTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
                readTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
                callTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
            }.newCall(request)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) {
                        return@runCatching null
                    }
                    response.body.string().toSelfInfoResult(source)
                }
        }.getOrElse { error ->
            if (error is CancellationException) {
                throw error
            }
            null
        }
    }

    private fun String.toSelfInfoResult(source: String): SelfInfoResult? {
        val response = json.decodeFromString(SelfInfoResponse.serializer(), this)
        val ip = response.ip?.takeIf { it.isNotBlank() } ?: return null
        val parsedOrg = parseOrg(response.org ?: response.asnOrg)
        val asn = response.asn?.normalizeAsn() ?: parsedOrg.asn
        return SelfInfoResult(
            ip = ip,
            asn = asn,
            org = parsedOrg.org,
            city = response.city?.takeIf { it.isNotBlank() },
            region = response.region?.takeIf { it.isNotBlank() } ?: response.regionName?.takeIf { it.isNotBlank() },
            country = response.country?.takeIf { it.isNotBlank() } ?: response.countryIso?.takeIf { it.isNotBlank() },
            source = source,
        )
    }

    companion object {
        private val AsnOrgPattern = Regex("""^(AS\d+)\s+(.+)$""", RegexOption.IGNORE_CASE)
        private const val Ipv4PartCount = 4

        fun parseOrg(org: String?): SelfInfoOrg =
            org
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { value ->
                    val match = AsnOrgPattern.matchEntire(value)
                    if (match != null) {
                        SelfInfoOrg(
                            asn = match.groupValues[1].uppercase(Locale.US),
                            org = match.groupValues[2].trim(),
                        )
                    } else {
                        SelfInfoOrg(asn = null, org = value)
                    }
                }
                ?: SelfInfoOrg(asn = null, org = null)

        fun maskIpForDisplay(ip: String): String {
            val parts = ip.split('.')
            return if (parts.size == Ipv4PartCount && parts.all { it.isNotBlank() }) {
                "${parts[0]}.${parts[1]}.xxx.xxx"
            } else {
                "redacted"
            }
        }

        private fun String.normalizeAsn(): String? =
            trim()
                .takeIf { it.isNotBlank() }
                ?.let { value ->
                    if (value.startsWith("AS", ignoreCase = true)) {
                        value.uppercase(Locale.US)
                    } else {
                        "AS$value"
                    }
                }
    }
}

@Serializable
private data class SelfInfoResponse(
    val ip: String? = null,
    val org: String? = null,
    val asn: String? = null,
    @SerialName("asn_org")
    val asnOrg: String? = null,
    val city: String? = null,
    val region: String? = null,
    @SerialName("region_name")
    val regionName: String? = null,
    val country: String? = null,
    @SerialName("country_iso")
    val countryIso: String? = null,
)
