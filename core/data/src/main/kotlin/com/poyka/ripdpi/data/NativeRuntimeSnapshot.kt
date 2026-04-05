package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable

@Serializable
data class LatencyPercentiles(
    val p50: Long,
    val p95: Long,
    val p99: Long,
    val min: Long,
    val max: Long,
    val count: Long,
)

@Serializable
data class LatencyDistributions(
    val dnsResolution: LatencyPercentiles? = null,
    val tcpConnect: LatencyPercentiles? = null,
    val tlsHandshake: LatencyPercentiles? = null,
)

@Serializable
data class NativeRuntimeEvent(
    val source: String,
    val level: String,
    val message: String,
    val createdAt: Long,
    val runtimeId: String? = null,
    val mode: String? = null,
    val policySignature: String? = null,
    val fingerprintHash: String? = null,
    val subsystem: String? = null,
)

@Serializable
data class NativeRuntimeSnapshot(
    val source: String,
    val state: String = "idle",
    val health: String = "idle",
    val activeSessions: Long = 0,
    val totalSessions: Long = 0,
    val totalErrors: Long = 0,
    val networkErrors: Long = 0,
    val routeChanges: Long = 0,
    val retryPacedCount: Long = 0,
    val lastRetryBackoffMs: Long? = null,
    val lastRetryReason: String? = null,
    val candidateDiversificationCount: Long = 0,
    val lastRouteGroup: Int? = null,
    val listenerAddress: String? = null,
    val upstreamAddress: String? = null,
    val upstreamRttMs: Long? = null,
    val profileId: String? = null,
    val protocolKind: String? = null,
    val tcpCapable: Boolean? = null,
    val udpCapable: Boolean? = null,
    val fallbackMode: String? = null,
    val lastHandshakeError: String? = null,
    val chainEntryState: String? = null,
    val chainExitState: String? = null,
    val resolverId: String? = null,
    val resolverProtocol: String? = null,
    val resolverEndpoint: String? = null,
    val resolverLatencyMs: Long? = null,
    val resolverLatencyAvgMs: Long? = null,
    val resolverFallbackActive: Boolean = false,
    val resolverFallbackReason: String? = null,
    val networkHandoverClass: String? = null,
    val lastTarget: String? = null,
    val lastHost: String? = null,
    val lastError: String? = null,
    val lastFailureClass: String? = null,
    val lastFallbackAction: String? = null,
    val dnsQueriesTotal: Long = 0,
    val dnsCacheHits: Long = 0,
    val dnsCacheMisses: Long = 0,
    val dnsFailuresTotal: Long = 0,
    val lastDnsHost: String? = null,
    val lastDnsError: String? = null,
    val autolearnEnabled: Boolean = false,
    val learnedHostCount: Int = 0,
    val penalizedHostCount: Int = 0,
    val blockedHostCount: Int = 0,
    val lastBlockSignal: String? = null,
    val lastBlockProvider: String? = null,
    val lastAutolearnHost: String? = null,
    val lastAutolearnGroup: Int? = null,
    val lastAutolearnAction: String? = null,
    val slotExhaustions: Long = 0,
    val tunnelStats: TunnelStats = TunnelStats(),
    val nativeEvents: List<NativeRuntimeEvent> = emptyList(),
    val latencyDistributions: LatencyDistributions? = null,
    val capturedAt: Long = 0,
) {
    companion object {
        fun idle(source: String): NativeRuntimeSnapshot = NativeRuntimeSnapshot(source = source)
    }
}

@Serializable
data class TunnelStats(
    val txPackets: Long = 0,
    val txBytes: Long = 0,
    val rxPackets: Long = 0,
    val rxBytes: Long = 0,
) {
    companion object {
        private const val RxBytesIndex = 3

        fun fromNative(stats: LongArray): TunnelStats =
            TunnelStats(
                txPackets = stats.getOrElse(0) { 0L },
                txBytes = stats.getOrElse(1) { 0L },
                rxPackets = stats.getOrElse(2) { 0L },
                rxBytes = stats.getOrElse(RxBytesIndex) { 0L },
            )
    }
}
