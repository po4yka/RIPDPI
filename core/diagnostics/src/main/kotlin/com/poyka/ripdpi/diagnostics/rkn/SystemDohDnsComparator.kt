package com.poyka.ripdpi.diagnostics.rkn

import com.poyka.ripdpi.data.NumericIp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
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
    private val dohResolver: Ipv4Resolver = DohReferenceResolver(),
    private val dnsMethodProvider: () -> DnsMethod = { DnsMethod.SYSTEM },
    private val systemTimeoutMs: Long = DefaultTimeoutMs,
    private val dohTimeoutMs: Long = DefaultTimeoutMs,
) {
    suspend fun compare(host: String): DnsComparisonResult {
        val system = runLookup(systemTimeoutMs) { systemResolver.resolve(host) }
        val dohIps = runLookup(dohTimeoutMs) { dohResolver.resolve(host) }.ips
        return evaluate(
            sysIps = system.ips,
            systemNegative = system.negative,
            dohIps = dohIps,
            dnsMethod = dnsMethodProvider(),
        )
    }

    private suspend fun runLookup(
        timeoutMs: Long,
        lookup: suspend () -> Set<String>,
    ): LookupOutcome =
        runCatching {
            withTimeout(timeoutMs) {
                lookup()
                    .asSequence()
                    .filter(String::isNotBlank)
                    .toCollection(linkedSetOf())
            }
        }.fold(
            onSuccess = { ips -> LookupOutcome(ips, negative = ips.isEmpty()) },
            onFailure = { error ->
                currentCoroutineContext().ensureActive()
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                // Only a "no such host" answer is a negative answer; a timeout or other error proves nothing.
                LookupOutcome(emptySet(), negative = isNegativeDnsAnswer(error))
            },
        )

    private fun evaluate(
        sysIps: Set<String>,
        systemNegative: Boolean,
        dohIps: Set<String>,
        dnsMethod: DnsMethod,
    ): DnsComparisonResult {
        val notes = mutableListOf<String>()
        val verdict: DnsComparisonVerdict
        val confidence: DnsComparisonConfidence

        when {
            sysIps.isEmpty() && dohIps.isNotEmpty() && systemNegative -> {
                verdict = DnsComparisonVerdict.DNS_BLOCK
                confidence = DnsComparisonConfidence.HIGH
                notes.add("System resolver failed while DoH returned A records")
            }

            sysIps.isEmpty() && dohIps.isNotEmpty() -> {
                verdict = DnsComparisonVerdict.DOWN
                confidence = DnsComparisonConfidence.LOW
                notes.add("System resolver timed out or failed without an answer - DNS block not confirmed")
            }

            sysIps.isEmpty() && dohIps.isEmpty() -> {
                verdict = DnsComparisonVerdict.DOWN
                confidence = DnsComparisonConfidence.LOW
                notes.add("Both system and DoH lookups failed")
            }

            sysIps.isNotEmpty() && dohIps.isEmpty() -> {
                verdict = DnsComparisonVerdict.OK
                confidence = DnsComparisonConfidence.LOW
                notes.add(
                    "DoH lookup failed on every reference resolver - control comparison unavailable, " +
                        "DNS poisoning cannot be ruled out",
                )
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

    private data class LookupOutcome(
        val ips: Set<String>,
        val negative: Boolean,
    )

    private companion object {
        private const val DefaultTimeoutMs = 5_000L
    }
}

/**
 * Android's getAllByName throws UnknownHostException for every getaddrinfo failure, with the (hidden)
 * android.system.GaiException as its cause, whose message names the code: "android_getaddrinfo failed:
 * EAI_NODATA (...)". Only EAI_NODATA and EAI_NONAME are a negative answer; EAI_AGAIN (SERVFAIL, upstream
 * timeout, no network) and every other failure prove nothing. The whole cause chain is read because
 * coroutine stack-trace recovery can wrap the original exception in a copy.
 */
private fun isNegativeDnsAnswer(error: Throwable): Boolean =
    error is UnknownHostException &&
        generateSequence(error.cause, Throwable::cause)
            .take(MaxCauseDepth)
            .any { cause -> cause.message?.let(NegativeGaiCode::containsMatchIn) == true }

private val NegativeGaiCode = Regex("""failed: EAI_NO(DATA|NAME)\b""")
private const val MaxCauseDepth = 4

class SystemIpv4Resolver : Ipv4Resolver {
    override suspend fun resolve(host: String): Set<String> =
        withContext(Dispatchers.IO) {
            val addresses = InetAddress.getAllByName(host)
            val ipv4 =
                addresses
                    .asSequence()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { address -> address.hostAddress }
                    .toCollection(linkedSetOf())
            // An IPv6-only answer is an answer, not a negative one.
            if (ipv4.isEmpty() && addresses.isNotEmpty()) throw IOException("System resolver returned no IPv4 address")
            ipv4
        }
}

/**
 * Public DoH resolvers with a JSON API on a fixed IP, so a reference lookup never depends on the system resolver.
 * They belong to independent providers: Cloudflare is blocked in Russia, so it cannot be the only reference.
 */
enum class DohReferenceEndpoint(
    val url: String,
) {
    GOOGLE("https://8.8.8.8/resolve"),
    ADGUARD_UNFILTERED("https://94.140.14.140/resolve"),
    CLOUDFLARE("https://1.1.1.1/dns-query"),
}

/**
 * Asks every reference provider at once and unions their answers. A blocked provider does not remove the
 * reference, and CDN answers that differ by provider do not read as a rewrite. Fails only when no provider answers.
 */
class DohReferenceResolver(
    private val resolvers: List<Ipv4Resolver> = DohReferenceEndpoint.entries.map { DohJsonResolver(it.url) },
) : Ipv4Resolver {
    override suspend fun resolve(host: String): Set<String> =
        coroutineScope {
            val answers = resolvers.map { resolver -> async { runCatching { resolver.resolve(host) } } }.awaitAll()
            currentCoroutineContext().ensureActive()
            // Only a failed provider is skipped: cancellation and JVM errors still propagate.
            answers.firstNotNullOfOrNull { it.exceptionOrNull() as? CancellationException }?.let { throw it }
            answers.firstNotNullOfOrNull { it.exceptionOrNull() as? Error }?.let { throw it }
            val answered = answers.mapNotNull { it.getOrNull() }
            if (answered.isEmpty()) {
                val cause = answers.firstNotNullOfOrNull { it.exceptionOrNull() }
                throw IOException("No DoH reference resolver answered", cause)
            }
            answered.flatMapTo(linkedSetOf()) { it }
        }
}

class DohJsonResolver(
    private val endpoint: String,
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
                // An error status is not an answer, so the reference resolver can tell it from an empty one.
                if (connection.responseCode !in HttpSuccessStatusRange) {
                    throw IOException("DoH reference $endpoint returned HTTP ${connection.responseCode}")
                }
                // A capped read keeps a slow or oversized provider answer from outliving the comparison budget.
                val body = ByteArrayOutputStream()
                connection.inputStream.use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        body.write(buffer, 0, count)
                        if (body.size() > MaxBodyBytes) throw IOException("DoH reference $endpoint answer is too large")
                    }
                }
                JSONObject(body.toString(StandardCharsets.UTF_8.name()))
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

    private fun isIpv4Literal(value: String): Boolean = NumericIp.parseAddress(value)?.isIPv4 == true

    private companion object {
        // Connect plus read stays under the comparator's 5 s budget, so a silent provider cannot void the others.
        private const val HttpTimeoutMs = 2_000
        private const val MaxBodyBytes = 16 * 1024
        private const val ARecordType = 1
        private val HttpSuccessStatusRange = 200..299
    }
}
