@file:Suppress("TooGenericExceptionCaught", "ReturnCount", "TooManyFunctions")

package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.CheckExecutionStatus
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
import com.poyka.ripdpi.core.detection.probe.publicIpOrNull
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.NumericIp
import com.poyka.ripdpi.data.diagnostics.DetectionAddressFamily
import com.poyka.ripdpi.data.diagnostics.DetectionResolverConfig
import com.poyka.ripdpi.data.diagnostics.DetectionResolverNetworkStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import java.io.IOException
import java.net.HttpURLConnection.HTTP_MULT_CHOICE
import java.net.HttpURLConnection.HTTP_OK
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

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
        dnsResolver: IpComparisonDnsResolver = ConfiguredIpComparisonDnsResolver(resolverConfig),
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
        val hasComparableFamily = ruIps.keys.any { family -> nonRuIps[family].orEmpty().isNotEmpty() }
        val incompleteCoverage =
            !hasComparableFamily || endpointResults.any { it.status == IpComparisonEndpointStatus.ERROR }
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
                } else if (hasComparableFamily) {
                    add(Finding("RU/non-RU reflected IPs agree within comparable address families"))
                } else if (successful.isNotEmpty()) {
                    add(Finding("No RU/non-RU comparison is available within one address family"))
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
                executionStatus =
                    if (incompleteCoverage) CheckExecutionStatus.INCONCLUSIVE else CheckExecutionStatus.COMPLETED,
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
                return endpointResult(
                    endpoint = endpoint,
                    dnsRecords = IpComparisonDnsRecords(),
                    status = IpComparisonEndpointStatus.ERROR,
                    errorMessage = "DNS failed: ${e.safeMessage()}",
                )
            }

        currentCoroutineContext().ensureActive()
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
            endpointResult(
                endpoint = endpoint,
                dnsRecords = dnsRecords,
                status = IpComparisonEndpointStatus.ERROR,
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
                val ip = result.reflectedIp?.let(::publicIpOrNull) ?: return@mapNotNull null
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
        publicIpOrNull(value) ?: throw IOException("Invalid public reflected IP")

    private fun addressFamilyOf(value: String): IpComparisonAddressFamily? =
        NumericIp.parseAddress(value)?.let {
            if (it.isIPv4) IpComparisonAddressFamily.IPV4 else IpComparisonAddressFamily.IPV6
        }

    private fun Throwable.safeMessage(): String = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName

    private const val CATEGORY_NAME = "IP Comparison"
}

class DefaultIpComparisonEndpointClient(
    private val resolverConfig: DetectionResolverConfig = DetectionResolverConfig(),
    private val networkStack: DetectionResolverNetworkStack = DetectionResolverNetworkStack(),
) : IpComparisonEndpointClient {
    override suspend fun fetchReflectedIp(endpoint: IpComparisonEndpointDescriptor): String {
        val request =
            Request
                .Builder()
                .url(endpoint.url)
                .header("Accept", "text/plain, application/json, */*")
                .build()
        val response =
            networkStack.executeCancellable(
                request,
                resolverConfig.copy(
                    addressFamily = endpoint.addressFamily.toDetectionAddressFamily(),
                    connectTimeoutMillis = TIMEOUT_MS.toLong(),
                    readTimeoutMillis = TIMEOUT_MS.toLong(),
                    callTimeoutMillis = TIMEOUT_MS.toLong(),
                ),
            )
        if (response.code !in HTTP_OK until HTTP_MULT_CHOICE) {
            throw IOException("HTTP ${response.code}")
        }
        return extractIp(response.body) ?: throw IOException("No reflected IP in response")
    }

    private fun extractIp(body: String): String? {
        publicIpOrNull(body)?.let { return it }
        val json =
            try {
                Json.parseToJsonElement(body)
            } catch (_: SerializationException) {
                return null
            }
        val values =
            when (json) {
                is JsonPrimitive -> listOf(json)
                is JsonObject -> listOfNotNull(json["ip"], json["query"])
                else -> emptyList()
            }
        val addresses =
            values.map { value ->
                (value as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?.let(::publicIpOrNull)
            }
        return addresses
            .takeIf { it.isNotEmpty() && it.none { address -> address == null } }
            ?.filterNotNull()
            ?.distinct()
            ?.singleOrNull()
    }

    private fun IpComparisonAddressFamily?.toDetectionAddressFamily(): DetectionAddressFamily =
        when (this) {
            IpComparisonAddressFamily.IPV4 -> DetectionAddressFamily.IPV4
            IpComparisonAddressFamily.IPV6 -> DetectionAddressFamily.IPV6
            null -> DetectionAddressFamily.ANY
        }

    private companion object {
        private const val TIMEOUT_MS = 5_000
    }
}

internal class ConfiguredIpComparisonDnsResolver(
    private val resolverConfig: DetectionResolverConfig,
    private val networkStack: DetectionResolverNetworkStack = DetectionResolverNetworkStack(),
) : IpComparisonDnsResolver {
    override suspend fun resolve(endpoint: IpComparisonEndpointDescriptor): IpComparisonDnsRecords {
        val host = URI(endpoint.url).host?.removeSurrounding("[", "]") ?: throw IOException("Endpoint URL has no host")
        val addresses =
            networkStack.resolveAddressesCancellable(
                host,
                resolverConfig.copy(addressFamily = DetectionAddressFamily.ANY),
            )
        return addresses.toComparisonRecords()
    }
}

class SystemIpComparisonDnsResolver : IpComparisonDnsResolver {
    override suspend fun resolve(endpoint: IpComparisonEndpointDescriptor): IpComparisonDnsRecords {
        val host = URI(endpoint.url).host ?: throw IOException("Endpoint URL has no host")
        return InetAddress.getAllByName(host).toList().toComparisonRecords()
    }
}

private fun List<InetAddress>.toComparisonRecords(): IpComparisonDnsRecords =
    IpComparisonDnsRecords(
        aRecords = filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.distinct(),
        aaaaRecords = filterIsInstance<Inet6Address>().mapNotNull { it.hostAddress }.distinct(),
    )
