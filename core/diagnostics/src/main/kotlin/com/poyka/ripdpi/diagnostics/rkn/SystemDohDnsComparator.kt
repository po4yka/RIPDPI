package com.poyka.ripdpi.diagnostics.rkn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class DnsComparisonVerdict {
    OK,
    DNS_BLOCK,
    DNS_REWRITE,
    DOWN,
}

enum class DnsComparisonConfidence {
    LOW,
    MEDIUM,
    HIGH,
}

enum class DnsMethod {
    SYSTEM,
    PRIVATE_DNS_DOT,
    PRIVATE_DNS_DOH,
}

data class DnsComparisonResult(
    val verdict: DnsComparisonVerdict,
    val confidence: DnsComparisonConfidence,
    val sysIps: Set<String>,
    val dohIps: Set<String>,
    val sysIp: String?,
    val dohIp: String?,
    val dnsMethod: DnsMethod,
    val notes: List<String> = emptyList(),
)

fun interface Ipv4Resolver {
    suspend fun resolve(host: String): Set<String>
}

class SystemDohDnsComparator(
    private val systemResolver: Ipv4Resolver = SystemIpv4Resolver(),
    private val dohResolver: Ipv4Resolver = CloudflareDohJsonResolver(),
    private val dnsMethodProvider: () -> DnsMethod = { DnsMethod.SYSTEM },
    private val systemTimeoutMs: Long = DefaultTimeoutMs,
    private val dohTimeoutMs: Long = DefaultTimeoutMs,
) {
    suspend fun compare(host: String): DnsComparisonResult {
        val sysIps = runLookup(systemTimeoutMs) { systemResolver.resolve(host) }
        val dohIps = runLookup(dohTimeoutMs) { dohResolver.resolve(host) }
        return evaluate(
            sysIps = sysIps,
            dohIps = dohIps,
            dnsMethod = dnsMethodProvider(),
        )
    }

    private suspend fun runLookup(
        timeoutMs: Long,
        lookup: suspend () -> Set<String>,
    ): Set<String> =
        runCatching {
            withTimeout(timeoutMs) {
                lookup()
                    .asSequence()
                    .filter(String::isNotBlank)
                    .toCollection(linkedSetOf())
            }
        }.getOrDefault(emptySet())

    private fun evaluate(
        sysIps: Set<String>,
        dohIps: Set<String>,
        dnsMethod: DnsMethod,
    ): DnsComparisonResult {
        val notes = mutableListOf<String>()
        val verdict: DnsComparisonVerdict
        val confidence: DnsComparisonConfidence

        when {
            sysIps.isEmpty() && dohIps.isNotEmpty() -> {
                verdict = DnsComparisonVerdict.DNS_BLOCK
                confidence = DnsComparisonConfidence.HIGH
                notes.add("System resolver failed while DoH returned A records")
            }

            sysIps.isEmpty() && dohIps.isEmpty() -> {
                verdict = DnsComparisonVerdict.DOWN
                confidence = DnsComparisonConfidence.LOW
                notes.add("Both system and DoH lookups failed")
            }

            sysIps.isNotEmpty() && dohIps.isEmpty() -> {
                verdict = DnsComparisonVerdict.OK
                confidence = DnsComparisonConfidence.LOW
                notes.add("DoH lookup failed - control comparison unavailable, DNS poisoning cannot be ruled out")
            }

            areDisjoint(sysIps, dohIps) -> {
                verdict = DnsComparisonVerdict.DNS_REWRITE
                confidence = DnsComparisonConfidence.MEDIUM
                notes.add("System resolver returned IPs not seen via DoH")
            }

            else -> {
                verdict = DnsComparisonVerdict.OK
                confidence = DnsComparisonConfidence.LOW
            }
        }

        return DnsComparisonResult(
            verdict = verdict,
            confidence = confidence,
            sysIps = sysIps,
            dohIps = dohIps,
            sysIp = sysIps.sorted().firstOrNull(),
            dohIp = dohIps.sorted().firstOrNull(),
            dnsMethod = dnsMethod,
            notes = notes,
        )
    }

    // Large domains rotate multi-A answers; only zero intersection is a rewrite signal.
    private fun areDisjoint(
        sysIps: Set<String>,
        dohIps: Set<String>,
    ): Boolean = sysIps.intersect(dohIps).isEmpty()

    private companion object {
        private const val DefaultTimeoutMs = 5_000L
    }
}

class SystemIpv4Resolver : Ipv4Resolver {
    override suspend fun resolve(host: String): Set<String> =
        withContext(Dispatchers.IO) {
            InetAddress
                .getAllByName(host)
                .asSequence()
                .filterIsInstance<Inet4Address>()
                .mapNotNull { address -> address.hostAddress }
                .toCollection(linkedSetOf())
        }
}

class CloudflareDohJsonResolver(
    private val endpoint: String = CloudflareDnsJsonEndpoint,
) : Ipv4Resolver {
    override suspend fun resolve(host: String): Set<String> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(host, StandardCharsets.UTF_8.name())
            val connection = URL("$endpoint?name=$encoded&type=A").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = HttpTimeoutMs
            connection.readTimeout = HttpTimeoutMs
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/dns-json")
            try {
                if (connection.responseCode !in 200..299) return@withContext emptySet()
                val body = connection.inputStream.bufferedReader().use { reader -> reader.readText() }
                JSONObject(body)
                    .optJSONArray("Answer")
                    ?.let { answers ->
                        (0 until answers.length()).mapNotNull { index ->
                            val answer = answers.optJSONObject(index) ?: return@mapNotNull null
                            answer
                                .takeIf { it.optInt("type") == ARecordType }
                                ?.optString("data")
                                ?.takeIf(::isIpv4Literal)
                        }
                    }.orEmpty()
                    .toCollection(linkedSetOf())
            } finally {
                connection.disconnect()
            }
        }

    private fun isIpv4Literal(value: String): Boolean =
        value
            .split('.')
            .takeIf { parts -> parts.size == Ipv4PartCount }
            ?.all { part -> part.toIntOrNull() in Ipv4ByteRange }
            ?: false

    private companion object {
        private const val CloudflareDnsJsonEndpoint = "https://cloudflare-dns.com/dns-query"
        private const val HttpTimeoutMs = 5_000
        private const val ARecordType = 1
        private const val Ipv4PartCount = 4
        private val Ipv4ByteRange = 0..255
    }
}
