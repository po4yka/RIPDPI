package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Serializable
import java.net.Inet6Address
import java.net.InetAddress

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
        val v4 = InetAddress.getByName(ipv4Address)
        val v6 = InetAddress.getByName(ipv6Address)
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

internal fun isIpProbeV4(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == IpProbeV4Octets &&
        parts.all { part ->
            part.isNotEmpty() && part.length <= IpProbeOctetDigits && part.all(Char::isDigit) &&
                part.toIntOrNull() in 0..IpProbeMaxOctet && (part == "0" || !part.startsWith('0'))
        }
}

internal fun isIpProbeV6(value: String): Boolean =
    value.length in 2..IpProbeMaxV6Length && ':' in value &&
        value.all { it in "0123456789abcdefABCDEF:" } &&
        runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)

private const val IpProbeV4Octets = 4
private const val IpProbeOctetDigits = 3
private const val IpProbeMaxOctet = 255
private const val IpProbeMaxV6Length = 39
internal const val IpProbeMaxPort = 65_535
internal const val IpProbeMaxTimeoutMs = 5_000L

private const val IpProbeMinTimeoutMs = 100L

private val IpProbeReservedV4 = setOf("255.255.255.255", "192.0.0.170", "192.0.0.171")
