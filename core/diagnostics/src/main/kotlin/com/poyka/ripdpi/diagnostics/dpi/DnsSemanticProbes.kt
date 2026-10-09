package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.diagnostics.DnsResponseOutcome
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

class DatagramSocketDnsUdpProbe(
    private val servers: List<String> = DefaultUdpDnsServers,
    private val timeoutMs: Int = DefaultUdpTimeoutMs,
    private val retries: Int = DefaultUdpRetries,
    private val retryDelayMs: Long = DefaultRetryDelayMs,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SemanticDnsUdpProbe {
    override suspend fun resolveResponse(domain: String): DnsProbeAnswer =
        withContext(dispatcher) {
            var last = dnsFailure(DnsResponseOutcome.NOT_OBSERVED)
            servers.forEach { server ->
                repeat(retries + 1) { attempt ->
                    currentCoroutineContext().ensureActive()
                    last = dnsAttempt { queryServer(domain, server) }
                    if (last.response?.rcode != null) return@withContext last
                    if (attempt < retries) delay(retryDelayMs)
                }
            }
            last
        }

    private fun queryServer(
        domain: String,
        server: String,
    ): DnsProbeAnswer {
        val query = DnsWireBuilder.buildQuery(domain)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetAddress.getByName(server), DnsPort)
            socket.send(DatagramPacket(query, query.size))
            val buffer = ByteArray(MaxDnsPayloadBytes)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            return DnsWireBuilder.parseResponse(buffer.copyOf(response.length), query)
        }
    }

    private companion object {
        private const val DnsPort = 53
        private const val MaxDnsPayloadBytes = 4096
        private const val DefaultUdpTimeoutMs = 3_000
        private const val DefaultUdpRetries = 2
        private const val DefaultRetryDelayMs = 500L
    }
}

class DohJsonAddressProbe(
    private val client: OkHttpClient = defaultDnsHttpClient(),
    private val endpoints: List<String> = DefaultDohJsonEndpoints,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = RipDpiJson,
) : SemanticDnsAddressProbe {
    override suspend fun resolveResponse(
        domain: String,
        excludedDohHostnames: Set<String>,
    ): DnsProbeAnswer =
        withContext(dispatcher) {
            queryDnsEndpoints(endpoints, excludedDohHostnames) { endpoint ->
                val encoded = URLEncoder.encode(domain, StandardCharsets.UTF_8.name())
                val request =
                    Request
                        .Builder()
                        .url("$endpoint?name=$encoded&type=A")
                        .get()
                        .header("Accept", "application/dns-json")
                        .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        DnsJsonResponseParser.parse(response.body.string(), domain, json)
                    } else {
                        dnsFailure(DnsResponseOutcome.TRANSPORT_ERROR)
                    }
                }
            }
        }
}

class DohWireAddressProbe(
    private val client: OkHttpClient = defaultDnsHttpClient(),
    private val endpoints: List<String> = DefaultDohWireEndpoints,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = DefaultDohWireTimeoutMs,
) : SemanticDnsAddressProbe {
    override suspend fun resolveResponse(
        domain: String,
        excludedDohHostnames: Set<String>,
    ): DnsProbeAnswer =
        withTimeoutOrNull(timeoutMs) {
            withContext(dispatcher) {
                queryDnsEndpoints(endpoints, excludedDohHostnames) { endpoint -> queryEndpoint(domain, endpoint) }
            }
        } ?: dnsFailure(DnsResponseOutcome.TIMEOUT)

    private fun queryEndpoint(
        domain: String,
        endpoint: String,
    ): DnsProbeAnswer {
        val query = DnsWireBuilder.buildQuery(domain)
        val post =
            Request
                .Builder()
                .url(endpoint)
                .post(query.toRequestBody(DnsMessageMediaType))
                .header("Accept", DnsMessageContentType)
                .build()
        val postResult =
            client.newCall(post).execute().use { response ->
                when {
                    response.isSuccessful -> DnsWireBuilder.parseResponse(response.body.bytes(), query)
                    response.code == MethodNotAllowed -> null
                    else -> dnsFailure(DnsResponseOutcome.TRANSPORT_ERROR)
                }
            }
        if (postResult != null) return postResult
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(query)
        val get =
            Request
                .Builder()
                .url("$endpoint?dns=$encoded")
                .get()
                .header("Accept", DnsMessageContentType)
                .build()
        return client.newCall(get).execute().use { response ->
            if (response.isSuccessful) {
                DnsWireBuilder.parseResponse(response.body.bytes(), query)
            } else {
                dnsFailure(DnsResponseOutcome.TRANSPORT_ERROR)
            }
        }
    }

    private companion object {
        private const val DefaultDohWireTimeoutMs = 7_000L
        private const val MethodNotAllowed = 405
    }
}

internal inline fun dnsAttempt(block: () -> DnsProbeAnswer): DnsProbeAnswer =
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: SocketTimeoutException) {
        dnsFailure(DnsResponseOutcome.TIMEOUT)
    } catch (_: IOException) {
        dnsFailure(DnsResponseOutcome.TRANSPORT_ERROR)
    } catch (_: SerializationException) {
        dnsFailure(DnsResponseOutcome.MALFORMED)
    } catch (_: IllegalArgumentException) {
        dnsFailure(DnsResponseOutcome.MALFORMED)
    }

private suspend fun queryDnsEndpoints(
    endpoints: List<String>,
    excluded: Set<String>,
    query: (String) -> DnsProbeAnswer,
): DnsProbeAnswer {
    var observed: DnsProbeAnswer? = null
    var last = dnsFailure(DnsResponseOutcome.NOT_OBSERVED)
    for (endpoint in endpoints.filterNot { URI(it).host in excluded }) {
        currentCoroutineContext().ensureActive()
        last = dnsAttempt { query(endpoint) }
        if (last.addresses.isNotEmpty()) return last
        if (observed == null && last.response?.rcode != null) observed = last
    }
    return observed ?: last
}

private val DefaultUdpDnsServers =
    listOf("8.8.8.8", "1.1.1.1", "9.9.9.9", "94.140.14.14", "77.88.8.8", "208.67.222.222")
private val DefaultDohJsonEndpoints = listOf("https://cloudflare-dns.com/dns-query", "https://dns.google/resolve")
private val DefaultDohWireEndpoints =
    listOf(
        "https://cloudflare-dns.com/dns-query",
        "https://dns.google/dns-query",
        "https://dns.adguard-dns.com/dns-query",
        "https://dns.quad9.net/dns-query",
    )
private const val DnsMessageContentType = "application/dns-message"
private val DnsMessageMediaType = DnsMessageContentType.toMediaType()

private fun defaultDnsHttpClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .connectTimeout(DefaultConnectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(DefaultReadTimeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(DefaultCallTimeoutSeconds, TimeUnit.SECONDS)
        .build()

private const val DefaultConnectTimeoutSeconds = 5L
private const val DefaultReadTimeoutSeconds = 5L
private const val DefaultCallTimeoutSeconds = 7L
