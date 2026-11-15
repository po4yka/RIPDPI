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
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
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

    private suspend fun queryServer(
        domain: String,
        server: String,
    ): DnsProbeAnswer {
        val query = DnsWireBuilder.buildQuery(domain, edns = true)
        return exchangeDnsDatagram(server, query, timeoutMs).answer
    }

    private companion object {
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
    private val timeoutMs: Long = DefaultDohJsonTimeoutMs,
) : SemanticDnsAddressProbe {
    override suspend fun resolveResponse(
        domain: String,
        excludedDohHostnames: Set<String>,
    ): DnsProbeAnswer =
        withContext(dispatcher) {
            queryDnsEndpoints(endpoints, excludedDohHostnames, timeoutMs) { endpoint ->
                val encoded = URLEncoder.encode(domain, StandardCharsets.UTF_8.name())
                val request =
                    Request
                        .Builder()
                        .url("$endpoint?name=$encoded&type=A")
                        .get()
                        .header("Accept", "application/dns-json")
                        .build()
                client.newCall(request).readDnsResponse { response ->
                    response.dnsAnswer { DnsJsonResponseParser.parse(response.readDnsJsonBody(), domain, json) }
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
        withContext(dispatcher) {
            queryDnsEndpoints(
                endpoints,
                excludedDohHostnames,
                timeoutMs,
            ) { endpoint -> queryEndpoint(domain, endpoint) }
        }

    private suspend fun queryEndpoint(
        domain: String,
        endpoint: String,
    ): DnsProbeAnswer {
        val query = DnsWireBuilder.buildQuery(domain, edns = true)
        val post =
            Request
                .Builder()
                .url(endpoint)
                .post(query.toRequestBody(DnsMessageMediaType))
                .header("Accept", DnsMessageContentType)
                .build()
        val postResult =
            client.newCall(post).readDnsResponse { response ->
                if (response.code == MethodNotAllowed) {
                    null
                } else {
                    response.dnsAnswer { DnsWireBuilder.parseResponse(response.readDnsWireBody(), query) }
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
        return client.newCall(get).readDnsResponse { response ->
            response.dnsAnswer { DnsWireBuilder.parseResponse(response.readDnsWireBody(), query) }
        }
    }

    private companion object {
        private const val DefaultDohWireTimeoutMs = 7_000L
        private const val MethodNotAllowed = 405
    }
}

private inline fun Response.dnsAnswer(read: () -> DnsProbeAnswer): DnsProbeAnswer =
    dnsAttempt {
        if (isSuccessful) read() else dnsFailure(DnsResponseOutcome.TRANSPORT_ERROR)
    }.copy(resolver = request.url.host)

internal inline fun dnsAttempt(block: () -> DnsProbeAnswer): DnsProbeAnswer =
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: InterruptedIOException) {
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
    timeoutMs: Long,
    query: suspend (String) -> DnsProbeAnswer,
): DnsProbeAnswer {
    var observed: DnsProbeAnswer? = null
    return withTimeoutOrNull(timeoutMs) {
        var last = dnsFailure(DnsResponseOutcome.NOT_OBSERVED)
        for (endpoint in endpoints.filterNot { URI(it).host in excluded }) {
            currentCoroutineContext().ensureActive()
            last = dnsAttempt { query(endpoint) }
            if (last.addresses.isNotEmpty()) return@withTimeoutOrNull last
            if (observed == null && last.response?.rcode != null) observed = last
        }
        observed ?: last
    } ?: (observed ?: dnsFailure(DnsResponseOutcome.TIMEOUT))
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

private const val DefaultDohJsonTimeoutMs = 14_000L
private const val DefaultConnectTimeoutSeconds = 5L
private const val DefaultReadTimeoutSeconds = 5L
private const val DefaultCallTimeoutSeconds = 7L
