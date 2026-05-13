@file:Suppress("TooGenericExceptionCaught", "ReturnCount", "TooManyFunctions")

package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.IpComparisonAddressFamily
import com.poyka.ripdpi.core.detection.IpComparisonDnsRecords
import com.poyka.ripdpi.core.detection.IpComparisonEndpointDescriptor
import com.poyka.ripdpi.core.detection.IpComparisonEndpointGroup
import com.poyka.ripdpi.core.detection.IpComparisonEndpointResult
import com.poyka.ripdpi.core.detection.IpComparisonEndpointStatus
import com.poyka.ripdpi.core.detection.IpComparisonResult
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.diagnostics.DetectionAddressFamily
import com.poyka.ripdpi.data.diagnostics.DetectionResolverConfig
import com.poyka.ripdpi.data.diagnostics.DetectionResolverNetworkStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

interface IpComparisonEndpointClient {
    suspend fun fetchReflectedIp(endpoint: IpComparisonEndpointDescriptor): String
}

interface IpComparisonDnsResolver {
    suspend fun resolve(endpoint: IpComparisonEndpointDescriptor): IpComparisonDnsRecords
}

object IpComparisonChecker {
    val DEFAULT_ENDPOINTS: List<IpComparisonEndpointDescriptor> =
        listOf(
            IpComparisonEndpointDescriptor(
                label = "2ip.ru",
                url = "https://2ip.ru",
                group = IpComparisonEndpointGroup.RU,
            ),
            IpComparisonEndpointDescriptor(
                label = "Yandex IPv4",
                url = "https://ipv4-internet.yandex.net/api/v0/ip",
                group = IpComparisonEndpointGroup.RU,
                addressFamily = IpComparisonAddressFamily.IPV4,
            ),
            IpComparisonEndpointDescriptor(
                label = "Yandex IPv6",
                url = "https://ipv6-internet.yandex.net/api/v0/ip",
                group = IpComparisonEndpointGroup.RU,
                addressFamily = IpComparisonAddressFamily.IPV6,
            ),
            IpComparisonEndpointDescriptor(
                label = "sypexgeo.net",
                url = "https://api.sypexgeo.net/json/",
                group = IpComparisonEndpointGroup.RU,
            ),
            IpComparisonEndpointDescriptor(
                label = "mail.ru",
                url = "https://ip.mail.ru",
                group = IpComparisonEndpointGroup.RU,
            ),
            IpComparisonEndpointDescriptor(
                label = "ifconfig.me IPv4",
                url = "https://ifconfig.me/ip",
                group = IpComparisonEndpointGroup.NON_RU,
                addressFamily = IpComparisonAddressFamily.IPV4,
            ),
            IpComparisonEndpointDescriptor(
                label = "ifconfig.me IPv6",
                url = "https://ifconfig.me/ip",
                group = IpComparisonEndpointGroup.NON_RU,
                addressFamily = IpComparisonAddressFamily.IPV6,
            ),
            IpComparisonEndpointDescriptor(
                label = "checkip.amazonaws.com",
                url = "https://checkip.amazonaws.com",
                group = IpComparisonEndpointGroup.NON_RU,
            ),
            IpComparisonEndpointDescriptor(
                label = "ipify.org",
                url = "https://api.ipify.org",
                group = IpComparisonEndpointGroup.NON_RU,
            ),
            IpComparisonEndpointDescriptor(
                label = "ip.sb IPv4",
                url = "https://api-ipv4.ip.sb/ip",
                group = IpComparisonEndpointGroup.NON_RU,
                addressFamily = IpComparisonAddressFamily.IPV4,
            ),
            IpComparisonEndpointDescriptor(
                label = "ip.sb IPv6",
                url = "https://api-ipv6.ip.sb/ip",
                group = IpComparisonEndpointGroup.NON_RU,
                addressFamily = IpComparisonAddressFamily.IPV6,
            ),
        )

    suspend fun check(
        dispatchers: AppCoroutineDispatchers,
        resolverConfig: DetectionResolverConfig = DetectionResolverConfig(),
        endpointClient: IpComparisonEndpointClient = DefaultIpComparisonEndpointClient(resolverConfig),
        dnsResolver: IpComparisonDnsResolver = SystemIpComparisonDnsResolver(),
        endpoints: List<IpComparisonEndpointDescriptor> = DEFAULT_ENDPOINTS,
    ): IpComparisonResult =
        withContext(dispatchers.io) {
            coroutineScope {
                val endpointResults =
                    endpoints
                        .map { endpoint ->
                            async {
                                probeEndpoint(
                                    endpoint = endpoint,
                                    endpointClient = endpointClient,
                                    dnsResolver = dnsResolver,
                                )
                            }
                        }.awaitAll()
                evaluate(endpointResults)
            }
        }

    internal fun evaluate(endpointResults: List<IpComparisonEndpointResult>): IpComparisonResult {
        val successful = endpointResults.filter { it.status == IpComparisonEndpointStatus.OK && it.reflectedIp != null }
        val ruIps = successful.reflectedIps(IpComparisonEndpointGroup.RU)
        val nonRuIps = successful.reflectedIps(IpComparisonEndpointGroup.NON_RU)
        val mismatches = mismatchedFamilies(ruIps, nonRuIps)
        val hasMismatch = mismatches.isNotEmpty()
        val findings =
            buildList {
                endpointResults.forEach { result -> add(endpointFinding(result)) }
                if (hasMismatch) {
                    add(
                        Finding(
                            description =
                                "RU/non-RU reflected IP mismatch: " +
                                    "RU=${ruIps.flattenForFinding()}, Non-RU=${nonRuIps.flattenForFinding()}",
                            needsReview = true,
                            source = EvidenceSource.IP_COMPARISON,
                            confidence = EvidenceConfidence.HIGH,
                        ),
                    )
                } else if (successful.isNotEmpty()) {
                    add(Finding("RU/non-RU reflected IPs agree or lack same-family comparison data"))
                } else {
                    add(Finding("No IP comparison endpoints returned a reflected IP"))
                }
            }
        val evidence =
            if (hasMismatch) {
                listOf(
                    EvidenceItem(
                        source = EvidenceSource.IP_COMPARISON,
                        detected = true,
                        confidence = EvidenceConfidence.HIGH,
                        description = "Russian and non-Russian IP reflection endpoints returned different public IPs",
                    ),
                )
            } else {
                emptyList()
            }
        val category =
            CategoryResult(
                name = CATEGORY_NAME,
                detected = false,
                findings = findings,
                needsReview = hasMismatch,
                evidence = evidence,
            )

        return IpComparisonResult(
            category = category,
            endpoints = endpointResults,
            ruReflectedIps = ruIps.values.flatten().toSet(),
            nonRuReflectedIps = nonRuIps.values.flatten().toSet(),
        )
    }

    private suspend fun probeEndpoint(
        endpoint: IpComparisonEndpointDescriptor,
        endpointClient: IpComparisonEndpointClient,
        dnsResolver: IpComparisonDnsResolver,
    ): IpComparisonEndpointResult {
        val dnsRecords =
            try {
                dnsResolver.resolve(endpoint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (endpoint.addressFamily == IpComparisonAddressFamily.IPV6) {
                    return endpointResult(
                        endpoint = endpoint,
                        dnsRecords = IpComparisonDnsRecords(),
                        status = IpComparisonEndpointStatus.SKIPPED,
                        errorMessage = "IPv6 DNS unavailable: ${e.safeMessage()}",
                    )
                }
                return endpointResult(
                    endpoint = endpoint,
                    dnsRecords = IpComparisonDnsRecords(),
                    status = IpComparisonEndpointStatus.ERROR,
                    errorMessage = "DNS failed: ${e.safeMessage()}",
                )
            }

        if (endpoint.addressFamily == IpComparisonAddressFamily.IPV6 && dnsRecords.aaaaRecords.isEmpty()) {
            return endpointResult(
                endpoint = endpoint,
                dnsRecords = dnsRecords,
                status = IpComparisonEndpointStatus.SKIPPED,
                errorMessage = "IPv6 DNS record unavailable",
            )
        }

        return try {
            val reflectedIp = normalizeIp(endpointClient.fetchReflectedIp(endpoint))
            endpointResult(
                endpoint = endpoint,
                dnsRecords = dnsRecords,
                reflectedIp = reflectedIp,
                status = IpComparisonEndpointStatus.OK,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status =
                if (endpoint.addressFamily == IpComparisonAddressFamily.IPV6 && e.isIpv6Unavailable()) {
                    IpComparisonEndpointStatus.SKIPPED
                } else {
                    IpComparisonEndpointStatus.ERROR
                }
            endpointResult(
                endpoint = endpoint,
                dnsRecords = dnsRecords,
                status = status,
                errorMessage = e.safeMessage(),
            )
        }
    }

    private fun endpointResult(
        endpoint: IpComparisonEndpointDescriptor,
        dnsRecords: IpComparisonDnsRecords,
        status: IpComparisonEndpointStatus,
        reflectedIp: String? = null,
        errorMessage: String? = null,
    ): IpComparisonEndpointResult =
        IpComparisonEndpointResult(
            descriptor = endpoint,
            dnsRecords = dnsRecords,
            reflectedIp = reflectedIp,
            status = status,
            errorMessage = errorMessage,
        )

    private fun endpointFinding(result: IpComparisonEndpointResult): Finding {
        val endpoint = result.descriptor
        val group = if (endpoint.group == IpComparisonEndpointGroup.RU) "RU" else "Non-RU"
        val records =
            "A=${result.dnsRecords.aRecords.joinToString().ifBlank { "none" }}, " +
                "AAAA=${result.dnsRecords.aaaaRecords.joinToString().ifBlank { "none" }}"
        val reflected = result.reflectedIp?.let { ", reflected=$it" }.orEmpty()
        val error = result.errorMessage?.let { ", reason=$it" }.orEmpty()
        return Finding("[${result.status}] ${endpoint.label} ($group): $records$reflected$error")
    }

    private fun List<IpComparisonEndpointResult>.reflectedIps(
        group: IpComparisonEndpointGroup,
    ): Map<IpComparisonAddressFamily, Set<String>> =
        filter { it.descriptor.group == group }
            .mapNotNull { result ->
                val ip = result.reflectedIp ?: return@mapNotNull null
                val family = addressFamilyOf(ip) ?: return@mapNotNull null
                family to ip
            }.groupBy({ it.first }, { it.second })
            .mapValues { (_, ips) -> ips.toSet() }

    private fun mismatchedFamilies(
        ruIps: Map<IpComparisonAddressFamily, Set<String>>,
        nonRuIps: Map<IpComparisonAddressFamily, Set<String>>,
    ): List<IpComparisonAddressFamily> =
        IpComparisonAddressFamily.entries.filter { family ->
            val ru = ruIps[family].orEmpty()
            val nonRu = nonRuIps[family].orEmpty()
            ru.isNotEmpty() && nonRu.isNotEmpty() && ru != nonRu
        }

    private fun Map<IpComparisonAddressFamily, Set<String>>.flattenForFinding(): String =
        values
            .flatten()
            .distinct()
            .joinToString()
            .ifBlank { "none" }

    private fun normalizeIp(value: String): String =
        value
            .trim()
            .trim('[', ']')
            .lowercase(Locale.US)

    private fun addressFamilyOf(value: String): IpComparisonAddressFamily? =
        try {
            when (InetAddress.getByName(value)) {
                is Inet4Address -> IpComparisonAddressFamily.IPV4
                is Inet6Address -> IpComparisonAddressFamily.IPV6
                else -> null
            }
        } catch (_: Exception) {
            null
        }

    private fun Exception.isIpv6Unavailable(): Boolean = this is SocketTimeoutException || this is IOException

    private fun Throwable.safeMessage(): String = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName

    private const val CATEGORY_NAME = "IP Comparison"
}

class DefaultIpComparisonEndpointClient(
    private val resolverConfig: DetectionResolverConfig = DetectionResolverConfig(),
    private val networkStack: DetectionResolverNetworkStack = DetectionResolverNetworkStack(),
) : IpComparisonEndpointClient {
    private val baseClient =
        OkHttpClient
            .Builder()
            .connectTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .build()

    override suspend fun fetchReflectedIp(endpoint: IpComparisonEndpointDescriptor): String {
        val request =
            Request
                .Builder()
                .url(endpoint.url)
                .header("Accept", "text/plain, application/json, */*")
                .header("User-Agent", "RIPDPI detection")
                .build()
        val client =
            networkStack
                .clientFor(
                    resolverConfig.copy(
                        addressFamily = endpoint.addressFamily.toDetectionAddressFamily(),
                        connectTimeoutMillis = TIMEOUT_MS.toLong(),
                        readTimeoutMillis = TIMEOUT_MS.toLong(),
                        callTimeoutMillis = TIMEOUT_MS.toLong(),
                    ),
                ).newBuilder()
                .connectionPool(baseClient.connectionPool)
                .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body.string()
            return extractIp(body) ?: throw IOException("No reflected IP in response")
        }
    }

    private fun extractIp(body: String): String? {
        IPV4_REGEX.find(body)?.let { return it.value }
        return body
            .split(IP_TOKEN_DELIMITER)
            .firstOrNull { token -> token.contains(":") && isIpv6(token) }
    }

    private fun isIpv6(token: String): Boolean =
        try {
            InetAddress.getByName(token) is Inet6Address
        } catch (_: Exception) {
            false
        }

    private fun IpComparisonAddressFamily?.toDetectionAddressFamily(): DetectionAddressFamily =
        when (this) {
            IpComparisonAddressFamily.IPV4 -> DetectionAddressFamily.IPV4
            IpComparisonAddressFamily.IPV6 -> DetectionAddressFamily.IPV6
            null -> DetectionAddressFamily.ANY
        }

    private companion object {
        private const val TIMEOUT_MS = 5_000
        private val IPV4_REGEX =
            Regex(
                """(?<![\d.])(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)""" +
                    """(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}(?![\d.])""",
            )
        private val IP_TOKEN_DELIMITER = Regex("""[\s,"'<>()\[\]{}]+""")
    }
}

class SystemIpComparisonDnsResolver : IpComparisonDnsResolver {
    override suspend fun resolve(endpoint: IpComparisonEndpointDescriptor): IpComparisonDnsRecords {
        val host = URI(endpoint.url).host ?: throw IOException("Endpoint URL has no host")
        val addresses = InetAddress.getAllByName(host).toList()
        return IpComparisonDnsRecords(
            aRecords = addresses.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.distinct(),
            aaaaRecords = addresses.filterIsInstance<Inet6Address>().mapNotNull { it.hostAddress }.distinct(),
        )
    }
}
