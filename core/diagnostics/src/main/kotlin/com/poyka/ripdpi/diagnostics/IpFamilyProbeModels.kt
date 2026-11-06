package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NumericIp
import kotlinx.serialization.Serializable

const val IpFamilyProfileId = "ip-family-connectivity"

@Serializable
data class IpFamilyProbeConfig(
    val version: Int = 1,
    val ipv4Address: String = "1.1.1.1",
    val ipv6Address: String = "2606:4700:4700::1111",
    val port: Int = 443,
    val timeoutMs: Long = 1_500,
) {
    internal fun validate() {
        require(version == 1 && port in 1..IpProbeMaxPort && timeoutMs in IpProbeMinTimeoutMs..IpProbeMaxTimeoutMs)
        require(isIpProbeV4(ipv4Address) && isIpProbeV6(ipv6Address))
        val v4 = requireNotNull(NumericIp.parseAddress(ipv4Address)).toInetAddress()
        val v6 = requireNotNull(NumericIp.parseAddress(ipv6Address)).toInetAddress()
        require(!v4.isAnyLocalAddress && !v4.isMulticastAddress && ipv4Address !in IpProbeReservedV4)
        require(!v6.isAnyLocalAddress && !v6.isMulticastAddress)
    }
}

@Serializable
enum class IpProbeFamily { IPV4, IPV6, NAT64 }

@Serializable
enum class IpProbeStage { TCP_CONNECT, DNS64_DISCOVERY }

@Serializable
enum class IpProbeStatus { REACHABLE, FAILED, TIMEOUT, NOT_OBSERVED, UNSUPPORTED, CANCELLED, INVALID }

@Serializable
enum class Nat64DiscoveryStatus { NOT_RUN, DISCOVERED, NO_PREFIX, FAILED, INVALID, TIMEOUT }

@Serializable
enum class IpProbePathScope { RAW_PATH, UNSUPPORTED_PROXY }

@Serializable
data class IpFamilyProbeEvidence(
    val version: Int = 1,
    val family: IpProbeFamily,
    val stage: IpProbeStage,
    val status: IpProbeStatus,
    val reason: String? = null,
    val durationMs: Long? = null,
    val destinationAddress: String? = null,
    val destinationPort: Int = 443,
    val prefix: String? = null,
    val prefixLength: Int? = null,
    val discoveryStatus: Nat64DiscoveryStatus? = null,
    val resolverSource: String? = null,
    val attemptCount: Int = 0,
    val pathScope: IpProbePathScope = IpProbePathScope.RAW_PATH,
)

internal fun isIpProbeV4(value: String): Boolean = NumericIp.parseAddress(value)?.isIPv4 == true

internal fun isIpProbeV6(value: String): Boolean =
    '.' !in value &&
        NumericIp.parseAddress(value)?.let { it.isIPv6 && !it.toIPv6().isIPv4Mapped } == true

internal const val IpProbeMaxPort = 65_535
internal const val IpProbeMaxTimeoutMs = 5_000L

private const val IpProbeMinTimeoutMs = 100L

private val IpProbeReservedV4 = setOf("255.255.255.255", "192.0.0.170", "192.0.0.171")
