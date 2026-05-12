package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random
import kotlin.system.measureTimeMillis

data class DiscoveredHost(
    val ip: String,
    val port: Int,
    val sourceSubnet: IpRange,
    val asn: Int?,
    val org: String?,
    val tcpTimeMs: Long,
    val tlsTimeMs: Long,
    val requestedHost: String? = null,
)

data class WebhostProbeResult(
    val tcpOk: Boolean,
    val tlsOk: Boolean,
    val tcpTimeMs: Long,
    val tlsTimeMs: Long,
    val error: String? = null,
)

fun interface WebhostProbe {
    suspend fun probe(
        ip: String,
        port: Int,
        sni: String?,
        tcpConnectTimeoutMs: Long,
        tlsHandshakeTimeoutMs: Long,
    ): WebhostProbeResult
}

class WebhostFarm(
    private val probe: WebhostProbe = OkHttpWebhostProbe(),
    private val metadata: SubnetMetadataLookup? = null,
    private val random: Random = Random.Default,
    private val maxCandidates: Int = DefaultMaxCandidates,
    private val tcpConnectTimeoutMs: Long = DefaultTcpConnectTimeoutMs,
    private val tlsHandshakeTimeoutMs: Long = DefaultTlsHandshakeTimeoutMs,
) {
    suspend fun discover(
        subnets: Set<IpRange>,
        count: Int,
        port: Int = HttpsPort,
        sni: String? = null,
        workers: Int = DefaultWorkers,
        randomHostname: Boolean = false,
    ): List<DiscoveredHost> {
        require(count >= 0) { "count must be non-negative" }
        require(maxCandidates > 0) { "maxCandidates must be positive" }
        if (count == 0 || subnets.isEmpty()) return emptyList()

        val candidates = sampleCandidates(subnets)
        val nextCandidate = AtomicInteger()
        val results = mutableListOf<DiscoveredHost>()
        val resultsMutex = Mutex()
        coroutineScope {
            val jobs =
                List(workers.coerceAtLeast(1)) {
                    async {
                        while (isActive) {
                            val candidate = candidates.getOrNull(nextCandidate.getAndIncrement()) ?: return@async
                            val requestedHost = if (randomHostname) RandomHostHeaderGenerator.next() else sni
                            resultsMutex.withLock {
                                if (results.size >= count) {
                                    return@async
                                }
                            }
                            val probeResult =
                                try {
                                    probe.probe(
                                        ip = candidate.ip,
                                        port = port,
                                        sni = requestedHost,
                                        tcpConnectTimeoutMs = tcpConnectTimeoutMs,
                                        tlsHandshakeTimeoutMs = tlsHandshakeTimeoutMs,
                                    )
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    continue
                                }
                            if (probeResult.tcpOk && probeResult.tlsOk) {
                                resultsMutex.withLock {
                                    if (results.size < count) {
                                        results += candidate.toDiscoveredHost(port, probeResult, requestedHost)
                                    }
                                }
                            }
                        }
                    }
                }
            jobs.awaitAll()
        }
        return results
    }

    private fun sampleCandidates(subnets: Set<IpRange>): List<WebhostCandidate> {
        val ranges = subnets.map { subnet -> ParsedIpv4Cidr.parse(subnet) }
        val totalHosts = ranges.sumOf { range -> range.hostCount }
        val targetCount = min(maxCandidates.toLong(), totalHosts).toInt()
        if (targetCount == 0) return emptyList()
        if (totalHosts <= maxCandidates) {
            return ranges
                .flatMap { range -> range.hosts() }
                .shuffled(random)
        }

        val sampled = LinkedHashSet<WebhostCandidate>()
        while (sampled.size < targetCount) {
            val globalOffset = random.nextLong(totalHosts)
            var remaining = globalOffset
            val range =
                ranges.first { parsed ->
                    if (remaining < parsed.hostCount) {
                        true
                    } else {
                        remaining -= parsed.hostCount
                        false
                    }
                }
            sampled += range.hostAt(remaining)
        }
        return sampled.toList()
    }

    private fun WebhostCandidate.toDiscoveredHost(
        port: Int,
        probeResult: WebhostProbeResult,
        requestedHost: String?,
    ): DiscoveredHost =
        DiscoveredHost(
            ip = ip,
            port = port,
            sourceSubnet = sourceSubnet,
            asn = metadata?.asnForIp(ip),
            org = metadata?.orgTermsForIp(ip)?.firstOrNull(),
            tcpTimeMs = probeResult.tcpTimeMs,
            tlsTimeMs = probeResult.tlsTimeMs,
            requestedHost = requestedHost,
        )

    companion object {
        private const val HttpsPort = 443
        private const val DefaultWorkers = 16
        private const val DefaultMaxCandidates = 10_000
        private const val DefaultTcpConnectTimeoutMs = 3_000L
        private const val DefaultTlsHandshakeTimeoutMs = 5_000L
    }
}

class OkHttpWebhostProbe(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clientBuilder: (OkHttpClient.Builder.() -> Unit) -> OkHttpClient =
        { configure -> OkHttpClient.Builder().apply(configure).build() },
) : WebhostProbe {
    override suspend fun probe(
        ip: String,
        port: Int,
        sni: String?,
        tcpConnectTimeoutMs: Long,
        tlsHandshakeTimeoutMs: Long,
    ): WebhostProbeResult =
        withContext(dispatcher) {
            var tcpTimeMs = 0L
            try {
                Socket().use { socket ->
                    tcpTimeMs =
                        measureTimeMillis {
                            socket.connect(InetSocketAddress(ip, port), tcpConnectTimeoutMs.toInt())
                        }
                }
                val tlsTimeMs =
                    measureTimeMillis {
                        tlsClient(ip, sni, tcpConnectTimeoutMs, tlsHandshakeTimeoutMs)
                            .newCall(tlsRequest(ip, port, sni))
                            .execute()
                            .close()
                    }
                WebhostProbeResult(tcpOk = true, tlsOk = true, tcpTimeMs = tcpTimeMs, tlsTimeMs = tlsTimeMs)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                WebhostProbeResult(
                    tcpOk = false,
                    tlsOk = false,
                    tcpTimeMs = tcpTimeMs,
                    tlsTimeMs = 0,
                    error = error.message ?: error.javaClass.simpleName,
                )
            }
        }

    private fun tlsClient(
        ip: String,
        sni: String?,
        tcpConnectTimeoutMs: Long,
        tlsHandshakeTimeoutMs: Long,
    ): OkHttpClient =
        clientBuilder {
            sni?.let { host -> dns(SingleHostDns(host = host, ip = ip)) }
            followRedirects(false)
            followSslRedirects(false)
            connectTimeout(tcpConnectTimeoutMs, TimeUnit.MILLISECONDS)
            readTimeout(tlsHandshakeTimeoutMs, TimeUnit.MILLISECONDS)
            callTimeout(tcpConnectTimeoutMs + tlsHandshakeTimeoutMs, TimeUnit.MILLISECONDS)
        }

    private fun tlsRequest(
        ip: String,
        port: Int,
        sni: String?,
    ): Request {
        val authority = sni ?: ip
        val url =
            if (port == HttpsPort) {
                "https://$authority/"
            } else {
                "https://$authority:$port/"
            }
        return Request
            .Builder()
            .url(url)
            .head()
            .header("Host", authority)
            .build()
    }

    private data class SingleHostDns(
        private val host: String,
        private val ip: String,
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname.equals(host, ignoreCase = true)) {
                listOf(InetAddress.getByName(ip))
            } else {
                Dns.SYSTEM.lookup(hostname)
            }
    }

    private companion object {
        private const val HttpsPort = 443
    }
}

private data class WebhostCandidate(
    val ip: String,
    val sourceSubnet: IpRange,
)

private data class ParsedIpv4Cidr(
    val source: IpRange,
    val firstHost: Long,
    val hostCount: Long,
) {
    fun hosts(): List<WebhostCandidate> = List(hostCount.toInt()) { offset -> hostAt(offset.toLong()) }

    fun hostAt(offset: Long): WebhostCandidate =
        WebhostCandidate(
            ip = longToIpv4(firstHost + offset),
            sourceSubnet = source,
        )

    companion object {
        fun parse(range: IpRange): ParsedIpv4Cidr {
            val parts = range.cidr.split("/")
            require(parts.size == 2) { "Invalid IPv4 CIDR: ${range.cidr}" }
            val address = ipv4ToLong(parts[0])
            val prefix = parts[1].toIntOrNull()
            require(prefix != null && prefix in 0..32) { "Invalid IPv4 CIDR prefix: ${range.cidr}" }
            val mask = if (prefix == 0) 0L else FullIpv4Mask shl (32 - prefix) and FullIpv4Mask
            val network = address and mask
            val size = 1L shl (32 - prefix)
            val firstHost =
                when {
                    prefix >= 31 -> network
                    else -> network + 1
                }
            val hostCount =
                when {
                    prefix >= 31 -> size
                    else -> (size - 2).coerceAtLeast(0)
                }
            return ParsedIpv4Cidr(source = range, firstHost = firstHost, hostCount = hostCount)
        }
    }
}

private const val FullIpv4Mask = 0xffff_ffffL

private fun ipv4ToLong(value: String): Long {
    val octets = value.split(".")
    require(octets.size == 4) { "Invalid IPv4 address: $value" }
    return octets.fold(0L) { acc, octet ->
        val number = octet.toIntOrNull()
        require(number != null && number in 0..255) { "Invalid IPv4 address: $value" }
        (acc shl 8) or number.toLong()
    }
}

private fun longToIpv4(value: Long): String =
    listOf(
        value shr 24,
        value shr 16,
        value shr 8,
        value,
    ).joinToString(".") { octet -> (octet and 0xff).toString() }

private fun Random.nextLong(bound: Long): Long {
    require(bound > 0) { "bound must be positive" }
    var bits: Long
    var value: Long
    do {
        bits = nextLong().ushr(1)
        value = bits % bound
    } while (bits - value + (bound - 1) < 0)
    return value
}
