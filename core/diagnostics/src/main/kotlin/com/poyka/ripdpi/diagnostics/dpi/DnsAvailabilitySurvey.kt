package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.TimeUnit

enum class DnsServerType {
    UDP,
    DOH_WIRE,
}

enum class DnsProbeStatus {
    OK,
    TIMEOUT,
    ERROR,
}

data class DnsServer(
    val name: String,
    val type: DnsServerType,
    val endpoint: String,
)

data class DnsProbeSample(
    val status: DnsProbeStatus,
    val latencyMs: Long? = null,
)

data class DnsServerResult(
    val name: String,
    val type: DnsServerType,
    val availableDomains: Int,
    val totalDomains: Int,
    val avgLatencyMs: Long?,
)

fun interface UdpAvailabilityProbe {
    suspend fun probe(
        server: DnsServer,
        domain: String,
    ): DnsProbeSample
}

interface DohWireAvailabilityProbe {
    suspend fun warmup(
        server: DnsServer,
        domain: String,
        timeoutMs: Long,
    )

    suspend fun probe(
        server: DnsServer,
        domain: String,
        timeoutMs: Long,
    ): DnsProbeSample
}

class DnsAvailabilitySurvey(
    private val servers: List<DnsServer> = defaultServers(),
    private val domains: List<String> = DefaultDomains,
    private val timeoutMs: Long = DefaultTimeoutMs,
    private val udpProbe: UdpAvailabilityProbe = DatagramUdpAvailabilityProbe(timeoutMs),
    private val dohProbe: DohWireAvailabilityProbe = OkHttpDohWireAvailabilityProbe(),
    private val udpConcurrency: Int = DefaultUdpConcurrency,
) {
    suspend fun run(): List<DnsServerResult> =
        coroutineScope {
            val udpSemaphore = Semaphore(udpConcurrency)
            servers
                .map { server ->
                    async {
                        when (server.type) {
                            DnsServerType.UDP -> udpSemaphore.withPermit { probeServer(server) }
                            DnsServerType.DOH_WIRE -> probeServer(server)
                        }
                    }
                }.awaitAll()
                .sortedWith(resultComparator)
        }

    private suspend fun probeServer(server: DnsServer): DnsServerResult {
        if (server.type == DnsServerType.DOH_WIRE) {
            runCatching {
                withTimeout(timeoutMs + HardTimeoutSlackMs) {
                    dohProbe.warmup(server, domains.first(), timeoutMs)
                }
            }
        }

        val samples =
            domains.map { domain ->
                when (server.type) {
                    DnsServerType.UDP -> {
                        runProbe { udpProbe.probe(server, domain) }
                    }

                    DnsServerType.DOH_WIRE -> {
                        runProbe {
                            withTimeout(timeoutMs + HardTimeoutSlackMs) {
                                dohProbe.probe(server, domain, timeoutMs)
                            }
                        }
                    }
                }
            }
        val latencies = samples.mapNotNull { sample -> sample.latencyMs.takeIf { sample.status == DnsProbeStatus.OK } }
        return DnsServerResult(
            name = server.name,
            type = server.type,
            availableDomains = latencies.size,
            totalDomains = domains.size,
            avgLatencyMs = latencies.takeIf { it.isNotEmpty() }?.average()?.toLong(),
        )
    }

    private suspend fun runProbe(block: suspend () -> DnsProbeSample): DnsProbeSample =
        runCatching { block() }.getOrElse { error ->
            when (error) {
                is SocketTimeoutException -> DnsProbeSample(DnsProbeStatus.TIMEOUT)
                else -> DnsProbeSample(DnsProbeStatus.TIMEOUT)
            }
        }

    companion object {
        fun defaultServers(): List<DnsServer> = DefaultServers

        private val resultComparator =
            compareByDescending<DnsServerResult> { result -> result.availableDomains > 0 }
                .thenBy { result -> result.avgLatencyMs ?: Long.MAX_VALUE }
                .thenBy { result -> result.name }

        private const val DefaultTimeoutMs = 3_000L
        private const val HardTimeoutSlackMs = 2_000L
        private const val DefaultUdpConcurrency = 15

        private val DefaultDomains = listOf("example.com", "vk.com", "ozon.ru", "habr.com", "mail.ru")

        private val DefaultServers =
            listOf(
                DnsServer("Google UDP", DnsServerType.UDP, "8.8.8.8"),
                DnsServer("Cloudflare UDP", DnsServerType.UDP, "1.1.1.1"),
                DnsServer("Quad9 UDP", DnsServerType.UDP, "9.9.9.9"),
                DnsServer("AdGuard UDP", DnsServerType.UDP, "94.140.14.14"),
                DnsServer("Yandex UDP", DnsServerType.UDP, "77.88.8.8"),
                DnsServer("OpenDNS UDP", DnsServerType.UDP, "208.67.222.222"),
                DnsServer("ControlD UDP", DnsServerType.UDP, "76.76.2.0"),
                DnsServer("CleanBrowsing UDP", DnsServerType.UDP, "185.228.168.9"),
                DnsServer("NextDNS UDP", DnsServerType.UDP, "45.90.28.0"),
                DnsServer("Google DoH", DnsServerType.DOH_WIRE, "https://dns.google/dns-query"),
                DnsServer("Cloudflare DoH", DnsServerType.DOH_WIRE, "https://cloudflare-dns.com/dns-query"),
                DnsServer("AdGuard DoH", DnsServerType.DOH_WIRE, "https://dns.adguard-dns.com/dns-query"),
                DnsServer("Quad9 DoH", DnsServerType.DOH_WIRE, "https://dns.quad9.net/dns-query"),
                DnsServer("OpenDNS DoH", DnsServerType.DOH_WIRE, "https://doh.opendns.com/dns-query"),
                DnsServer("Yandex DoH", DnsServerType.DOH_WIRE, "https://common.dot.dns.yandex.net/dns-query"),
                DnsServer("NextDNS DoH", DnsServerType.DOH_WIRE, "https://dns.nextdns.io/dns-query"),
                DnsServer(
                    "CleanBrowsing DoH",
                    DnsServerType.DOH_WIRE,
                    "https://doh.cleanbrowsing.org/doh/family-filter/",
                ),
                DnsServer("DNS.SB DoH", DnsServerType.DOH_WIRE, "https://doh.dns.sb/dns-query"),
                DnsServer("LibreDNS DoH", DnsServerType.DOH_WIRE, "https://doh.libredns.gr/dns-query"),
                DnsServer("Mullvad DoH", DnsServerType.DOH_WIRE, "https://base.dns.mullvad.net/dns-query"),
                DnsServer("DNS0 DoH", DnsServerType.DOH_WIRE, "https://dns0.eu/dns-query"),
            )
    }
}

class DatagramUdpAvailabilityProbe(
    private val timeoutMs: Long,
) : UdpAvailabilityProbe {
    override suspend fun probe(
        server: DnsServer,
        domain: String,
    ): DnsProbeSample =
        withContext(Dispatchers.IO) {
            val transactionId = DnsWireBuilder.randomTransactionId()
            val query = DnsWireBuilder.buildQuery(domain, transactionId)
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs.toInt()
                val address = InetAddress.getByName(server.endpoint)
                socket.send(DatagramPacket(query, query.size, address, DnsPort))
                val buffer = ByteArray(MaxDnsPacketBytes)
                val response = DatagramPacket(buffer, buffer.size)
                val startNs = System.nanoTime()
                socket.receive(response)
                val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs).coerceAtLeast(1)
                val records = DnsWireBuilder.parseResponse(buffer.copyOf(response.length), transactionId)
                if (records.any(::isIpv4Literal)) {
                    DnsProbeSample(DnsProbeStatus.OK, latencyMs)
                } else {
                    DnsProbeSample(DnsProbeStatus.ERROR)
                }
            }
        }

    private companion object {
        const val DnsPort = 53
        const val MaxDnsPacketBytes = 512
    }
}

class OkHttpDohWireAvailabilityProbe(
    private val clientBuilder: (OkHttpClient.Builder.() -> Unit) -> OkHttpClient =
        { configure -> OkHttpClient.Builder().apply(configure).build() },
) : DohWireAvailabilityProbe {
    override suspend fun warmup(
        server: DnsServer,
        domain: String,
        timeoutMs: Long,
    ) {
        runCatching { probe(server, domain, timeoutMs) }
    }

    override suspend fun probe(
        server: DnsServer,
        domain: String,
        timeoutMs: Long,
    ): DnsProbeSample =
        withContext(Dispatchers.IO) {
            val client =
                clientBuilder {
                    connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                }
            val transactionId = DnsWireBuilder.randomTransactionId()
            val query = DnsWireBuilder.buildQuery(domain, transactionId)
            val post = buildPostRequest(server.endpoint, query)
            val startNs = System.nanoTime()
            client.newCall(post).execute().use { response ->
                if (response.code != MethodNotAllowed) {
                    return@withContext decodeDohResponse(response.body.bytes(), transactionId, startNs)
                }
            }
            val get = buildGetRequest(server.endpoint, query)
            client.newCall(get).execute().use { response ->
                decodeDohResponse(response.body.bytes(), transactionId, startNs)
            }
        }

    private fun decodeDohResponse(
        bytes: ByteArray,
        transactionId: Int,
        startNs: Long,
    ): DnsProbeSample {
        val records = DnsWireBuilder.parseResponse(bytes, transactionId)
        return if (records.any(::isIpv4Literal)) {
            DnsProbeSample(
                status = DnsProbeStatus.OK,
                latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs).coerceAtLeast(1),
            )
        } else {
            DnsProbeSample(DnsProbeStatus.ERROR)
        }
    }

    private fun buildPostRequest(
        url: String,
        query: ByteArray,
    ): Request =
        Request
            .Builder()
            .url(url)
            .post(query.toRequestBody(DnsMessageMediaType))
            .header("Accept", DnsMessageContentType)
            .build()

    private fun buildGetRequest(
        url: String,
        query: ByteArray,
    ): Request {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(query)
        return Request
            .Builder()
            .url("$url?dns=$encoded")
            .get()
            .header("Accept", DnsMessageContentType)
            .build()
    }

    private companion object {
        const val DnsMessageContentType = "application/dns-message"
        const val MethodNotAllowed = 405
        val DnsMessageMediaType = DnsMessageContentType.toMediaType()
    }
}

private fun isIpv4Literal(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { part -> part.toIntOrNull() in 0..255 }
}
