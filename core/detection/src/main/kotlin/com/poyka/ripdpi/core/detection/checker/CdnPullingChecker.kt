package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.CdnPullingAddressFamily
import com.poyka.ripdpi.core.detection.CdnPullingEndpointDescriptor
import com.poyka.ripdpi.core.detection.CdnPullingEndpointResult
import com.poyka.ripdpi.core.detection.CdnPullingEndpointStatus
import com.poyka.ripdpi.core.detection.CdnPullingResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.diagnostics.DetectionAddressFamily
import com.poyka.ripdpi.data.diagnostics.DetectionResolverConfig
import com.poyka.ripdpi.data.diagnostics.DetectionResolverNetworkStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

fun interface CdnTraceClient {
    suspend fun fetchTrace(endpoint: CdnPullingEndpointDescriptor): String
}

object CdnPullingChecker {
    val DEFAULT_ENDPOINTS: List<CdnPullingEndpointDescriptor> =
        listOf(
            endpoint(
                label = "Google Video IPv4",
                url = "https://redirector.googlevideo.com/report_mapping",
                targetHost = "redirector.googlevideo.com",
                addressFamily = CdnPullingAddressFamily.IPV4,
            ),
            endpoint(
                label = "Google Video IPv6",
                url = "https://redirector.googlevideo.com/report_mapping",
                targetHost = "redirector.googlevideo.com",
                addressFamily = CdnPullingAddressFamily.IPV6,
            ),
            endpoint(
                label = "Cloudflare IPv4",
                url = "https://cloudflare.com/cdn-cgi/trace",
                targetHost = "cloudflare.com",
                addressFamily = CdnPullingAddressFamily.IPV4,
            ),
            endpoint(
                label = "Cloudflare IPv6",
                url = "https://cloudflare.com/cdn-cgi/trace",
                targetHost = "cloudflare.com",
                addressFamily = CdnPullingAddressFamily.IPV6,
            ),
            endpoint(
                label = "1.1.1.1 IPv4",
                url = "https://one.one.one.one/cdn-cgi/trace",
                targetHost = "one.one.one.one",
                addressFamily = CdnPullingAddressFamily.IPV4,
            ),
            endpoint(
                label = "1.1.1.1 IPv6",
                url = "https://one.one.one.one/cdn-cgi/trace",
                targetHost = "one.one.one.one",
                addressFamily = CdnPullingAddressFamily.IPV6,
            ),
            endpoint(
                label = "RuTracker IPv4",
                url = "https://rutracker.org/cdn-cgi/trace",
                targetHost = "rutracker.org",
                addressFamily = CdnPullingAddressFamily.IPV4,
            ),
            endpoint(
                label = "RuTracker IPv6",
                url = "https://rutracker.org/cdn-cgi/trace",
                targetHost = "rutracker.org",
                addressFamily = CdnPullingAddressFamily.IPV6,
            ),
            endpoint(
                label = "Meduza IPv4",
                url = "https://meduza.io/cdn-cgi/trace",
                targetHost = "meduza.io",
                addressFamily = CdnPullingAddressFamily.IPV4,
            ),
            endpoint(
                label = "Meduza IPv6",
                url = "https://meduza.io/cdn-cgi/trace",
                targetHost = "meduza.io",
                addressFamily = CdnPullingAddressFamily.IPV6,
            ),
        )

    suspend fun check(
        dispatchers: AppCoroutineDispatchers,
        enabled: Boolean,
        resolverConfig: DetectionResolverConfig = DetectionResolverConfig(),
        endpointClient: CdnTraceClient = DefaultCdnTraceClient(resolverConfig),
        endpoints: List<CdnPullingEndpointDescriptor> = DEFAULT_ENDPOINTS,
    ): CdnPullingResult =
        withContext(dispatchers.io) {
            if (!enabled) {
                return@withContext disabledResult()
            }
            coroutineScope {
                endpoints
                    .map { endpoint ->
                        async {
                            probeEndpoint(endpoint, endpointClient)
                        }
                    }.awaitAll()
                    .let(::evaluate)
            }
        }

    internal fun evaluate(endpointResults: List<CdnPullingEndpointResult>): CdnPullingResult {
        val tlsMitmResults = endpointResults.filter { it.tlsMitm }
        val mismatchedTargets = mismatchedTargets(endpointResults)
        val findings =
            buildList {
                endpointResults.forEach { result -> add(result.toFinding()) }
                if (tlsMitmResults.isNotEmpty()) {
                    add(
                        Finding(
                            description =
                                "DPI_MITM: TLS certificate validation failed for " +
                                    tlsMitmResults.joinToString { it.descriptor.targetHost },
                            detected = true,
                            source = EvidenceSource.CDN_PULLING,
                            confidence = EvidenceConfidence.HIGH,
                        ),
                    )
                }
                if (mismatchedTargets.isNotEmpty()) {
                    add(
                        Finding(
                            description = "CDN IPv4/IPv6 exit mismatch: ${mismatchedTargets.joinToString()}",
                            needsReview = true,
                            source = EvidenceSource.CDN_PULLING,
                            confidence = EvidenceConfidence.HIGH,
                        ),
                    )
                }
                if (isEmpty()) {
                    add(Finding("No CDN trace endpoints were checked"))
                }
            }
        val evidence =
            buildList {
                if (tlsMitmResults.isNotEmpty()) {
                    add(
                        EvidenceItem(
                            source = EvidenceSource.CDN_PULLING,
                            detected = true,
                            confidence = EvidenceConfidence.HIGH,
                            description = "CDN trace HTTPS endpoints reported TLS MITM evidence",
                        ),
                    )
                }
                if (mismatchedTargets.isNotEmpty()) {
                    add(
                        EvidenceItem(
                            source = EvidenceSource.CDN_PULLING,
                            detected = true,
                            confidence = EvidenceConfidence.HIGH,
                            description = "CDN trace endpoints returned different IPv4 and IPv6 exit IPs",
                        ),
                    )
                }
            }
        return CdnPullingResult(
            category =
                CategoryResult(
                    name = CATEGORY_NAME,
                    detected = tlsMitmResults.isNotEmpty(),
                    needsReview = mismatchedTargets.isNotEmpty(),
                    findings = findings,
                    evidence = evidence,
                ),
            endpoints = endpointResults,
            actionableTargets = mismatchedTargets,
        )
    }

    private suspend fun probeEndpoint(
        endpoint: CdnPullingEndpointDescriptor,
        endpointClient: CdnTraceClient,
    ): CdnPullingEndpointResult =
        try {
            val body = endpointClient.fetchTrace(endpoint)
            val reflectedIp = extractIp(body) ?: throw IOException("No CDN reflected IP in response")
            CdnPullingEndpointResult(
                descriptor = endpoint,
                reflectedIp = reflectedIp,
                status = CdnPullingEndpointStatus.OK,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SSLException) {
            CdnPullingEndpointResult(
                descriptor = endpoint,
                reflectedIp = null,
                status = CdnPullingEndpointStatus.ERROR,
                errorMessage = e.safeMessage(),
                tlsMitm = true,
            )
        } catch (e: Exception) {
            CdnPullingEndpointResult(
                descriptor = endpoint,
                reflectedIp = null,
                status =
                    if (endpoint.addressFamily == CdnPullingAddressFamily.IPV6 && e.isIpv6Unavailable()) {
                        CdnPullingEndpointStatus.SKIPPED
                    } else {
                        CdnPullingEndpointStatus.ERROR
                    },
                errorMessage = e.safeMessage(),
            )
        }

    private fun mismatchedTargets(results: List<CdnPullingEndpointResult>): List<String> =
        results
            .filter { it.status == CdnPullingEndpointStatus.OK && it.reflectedIp != null }
            .groupBy { it.descriptor.targetHost }
            .filter { (_, targetResults) ->
                val v4 = targetResults.ipsFor(CdnPullingAddressFamily.IPV4)
                val v6 = targetResults.ipsFor(CdnPullingAddressFamily.IPV6)
                v4.isNotEmpty() && v6.isNotEmpty() && v4 != v6
            }.keys
            .sorted()

    private fun List<CdnPullingEndpointResult>.ipsFor(family: CdnPullingAddressFamily): Set<String> =
        filter { it.descriptor.addressFamily == family }
            .mapNotNull { it.reflectedIp }
            .toSet()

    private fun CdnPullingEndpointResult.toFinding(): Finding {
        val reflected = reflectedIp?.let { ", reflected=$it" }.orEmpty()
        val error = errorMessage?.let { ", reason=$it" }.orEmpty()
        return Finding(
            "[${status.name}] ${descriptor.label}: " +
                "${descriptor.addressFamily.name.lowercase(Locale.US)}$reflected$error",
        )
    }

    private fun disabledResult(): CdnPullingResult =
        CdnPullingResult(
            category =
                CategoryResult(
                    name = CATEGORY_NAME,
                    detected = false,
                    findings = listOf(Finding("CDN pulling check disabled")),
                ),
            endpoints = emptyList(),
        )

    private fun extractIp(body: String): String? {
        IPV4_REGEX.find(body)?.let { return it.value }
        return body
            .split(IP_TOKEN_DELIMITER)
            .firstOrNull(::isIpv6)
    }

    private fun isIpv6(token: String): Boolean =
        try {
            token.contains(":") && InetAddress.getByName(token).hostAddress != null
        } catch (_: Exception) {
            false
        }

    private fun Throwable.safeMessage(): String = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName

    private fun Exception.isIpv6Unavailable(): Boolean = this is IOException

    private fun endpoint(
        label: String,
        url: String,
        targetHost: String,
        addressFamily: CdnPullingAddressFamily,
    ): CdnPullingEndpointDescriptor =
        CdnPullingEndpointDescriptor(
            label = label,
            url = url,
            targetHost = targetHost,
            addressFamily = addressFamily,
        )

    private const val CATEGORY_NAME = "CDN Pulling"
    private val IPV4_REGEX =
        Regex(
            """(?<![\d.])(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)""" +
                """(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}(?![\d.])""",
        )
    private val IP_TOKEN_DELIMITER = Regex("""[\s,"'<>()\[\]{}=]+""")
}

class DefaultCdnTraceClient(
    private val resolverConfig: DetectionResolverConfig = DetectionResolverConfig(),
    private val networkStack: DetectionResolverNetworkStack = DetectionResolverNetworkStack(),
) : CdnTraceClient {
    override suspend fun fetchTrace(endpoint: CdnPullingEndpointDescriptor): String {
        val request =
            Request
                .Builder()
                .url(endpoint.url)
                .header("Accept", "text/plain, application/json, */*")
                .header("User-Agent", "RIPDPI detection")
                .build()
        val client =
            networkStack.clientFor(
                resolverConfig.copy(
                    addressFamily = endpoint.addressFamily.toDetectionAddressFamily(),
                    connectTimeoutMillis = TIMEOUT_MS.toLong(),
                    readTimeoutMillis = TIMEOUT_MS.toLong(),
                    callTimeoutMillis = TIMEOUT_MS.toLong(),
                ),
            )
        client
            .newBuilder()
            .connectTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}")
                }
                return response.body.string()
            }
    }

    private fun CdnPullingAddressFamily.toDetectionAddressFamily(): DetectionAddressFamily =
        when (this) {
            CdnPullingAddressFamily.IPV4 -> DetectionAddressFamily.IPV4
            CdnPullingAddressFamily.IPV6 -> DetectionAddressFamily.IPV6
        }

    private companion object {
        private const val TIMEOUT_MS = 5_000
    }
}
