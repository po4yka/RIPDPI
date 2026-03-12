package com.poyka.ripdpi.core

import kotlinx.serialization.Serializable

@Serializable
data class NativeRuntimeEvent(
    val source: String,
    val level: String,
    val message: String,
    val createdAt: Long,
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
    val lastRouteGroup: Int? = null,
    val listenerAddress: String? = null,
    val upstreamAddress: String? = null,
    val resolverId: String? = null,
    val lastTarget: String? = null,
    val lastHost: String? = null,
    val lastError: String? = null,
    val dnsQueriesTotal: Long = 0,
    val dnsCacheHits: Long = 0,
    val dnsCacheMisses: Long = 0,
    val dnsFailuresTotal: Long = 0,
    val lastDnsHost: String? = null,
    val lastDnsError: String? = null,
    val autolearnEnabled: Boolean = false,
    val learnedHostCount: Int = 0,
    val penalizedHostCount: Int = 0,
    val lastAutolearnHost: String? = null,
    val lastAutolearnGroup: Int? = null,
    val lastAutolearnAction: String? = null,
    val tunnelStats: TunnelStats = TunnelStats(),
    val nativeEvents: List<NativeRuntimeEvent> = emptyList(),
    val capturedAt: Long = 0,
) {
    companion object {
        fun idle(source: String): NativeRuntimeSnapshot = NativeRuntimeSnapshot(source = source)
    }
}
