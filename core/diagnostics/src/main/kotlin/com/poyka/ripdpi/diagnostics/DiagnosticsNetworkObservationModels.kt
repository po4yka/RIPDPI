package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Serializable

@Serializable
data class DnsObservationFact(
    val domain: String,
    val status: DnsObservationStatus,
    val udpAddresses: List<String> = emptyList(),
    val encryptedAddresses: List<String> = emptyList(),
    val udpLatencyMs: Long? = null,
    val encryptedLatencyMs: Long? = null,
    val tamperingScore: Int? = null,
    val responseAnomalySignals: List<String>? = null,
    val cnameTargets: List<String>? = null,
    val udpResponseSize: Int? = null,
    val udpHasEdns0: Boolean? = null,
    val comparisonScore: Int? = null,
    val recordTypeMismatch: Boolean? = null,
    val malformedPointers: Boolean? = null,
    val injectionLatencyRatio: Long? = null,
    val forgedAddresses: List<String>? = null,
    val forgedAddressPool: String? = null,
)

@Serializable
data class TcpObservationFact(
    val provider: String,
    val status: TcpProbeStatus,
    val selectedSni: String? = null,
    val bytesSent: Int? = null,
    val responsesSeen: Int? = null,
    val freezeThresholdBytes: Int? = null,
    val port: Int? = null,
    val altPort: Int? = null,
    val altPortStatus: String? = null,
    val tcpBlockMethod: String? = null,
    val observedWindowSize: Int? = null,
    val rstTimingMs: Long? = null,
    val synAckLatencyMs: Long? = null,
    val rstOrigin: String? = null,
)
