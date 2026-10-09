package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Serializable

const val PmtuProfileId = "pmtu-connectivity"

@Serializable
data class PmtuProbeConfig(
    val version: Int = 1,
    val host: String = "www.cloudflare.com",
    val port: Int = 443,
    val connectIpv4: String? = null,
    val connectIpv6: String? = null,
    val timeoutMs: Long = 10_000,
    val observationMs: Long = 3_000,
    val upperBoundUdpPayloadBytes: Int = 1_472,
) {
    internal fun validate() {
        require(version == 1 && isHttp3Host(host) && port in 1..IpProbeMaxPort)
        require(connectIpv4 == null || isIpProbeV4(connectIpv4))
        require(connectIpv6 == null || isIpProbeV6(connectIpv6))
        require(timeoutMs in PmtuMinWindowMs..PmtuMaxTimeoutMs)
        require(observationMs in PmtuMinWindowMs..PmtuMaxObservationMs && observationMs <= timeoutMs)
        require(upperBoundUdpPayloadBytes in (PmtuInitialPayloadBytes + 1)..PmtuMaxPayloadBytes)
    }
}

@Serializable
enum class PmtuAddressFamily { IPV4, IPV6 }

@Serializable
enum class PmtuProbeStatus { OBSERVED, INCONCLUSIVE, FAILED, TIMEOUT, CANCELLED, UNSUPPORTED, INVALID, NOT_OBSERVED }

@Serializable
enum class PmtuPathScope { RAW_PATH, UNSUPPORTED_PROXY }

@Serializable
data class PmtuProbeEvidence(
    val version: Int = 1,
    val method: String = "QUIC_DPLPMTUD",
    val addressFamily: PmtuAddressFamily,
    val status: PmtuProbeStatus,
    val reason: String? = null,
    val peerAddress: String? = null,
    val tlsValidated: Boolean = false,
    val alpn: String? = null,
    val fragmentationPrevented: Boolean = false,
    val initialUdpPayloadBytes: Int = PmtuInitialPayloadBytes,
    val configuredUpperBoundUdpPayloadBytes: Int = PmtuMaxPayloadBytes,
    val currentUdpPayloadBytes: Int? = null,
    val acknowledgedUdpPayloadLowerBoundBytes: Int? = null,
    val sentProbeCount: Long = 0,
    val lostProbeCount: Long = 0,
    val blackHoleCount: Long = 0,
    val observationWindowComplete: Boolean = false,
    val configuredUpperBoundReached: Boolean = false,
    val durationMs: Long? = null,
    val handshakeElapsedMs: Long? = null,
    val pathScope: PmtuPathScope = PmtuPathScope.RAW_PATH,
)

internal const val PmtuInitialPayloadBytes = 1_200
internal const val PmtuMaxPayloadBytes = 1_472
internal const val PmtuMinWindowMs = 250L
internal const val PmtuMaxTimeoutMs = 15_000L
private const val PmtuMaxObservationMs = 10_000L
