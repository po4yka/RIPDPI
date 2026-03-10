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
    val routeChanges: Long = 0,
    val lastRouteGroup: Int? = null,
    val listenerAddress: String? = null,
    val upstreamAddress: String? = null,
    val lastTarget: String? = null,
    val lastHost: String? = null,
    val lastError: String? = null,
    val tunnelStats: TunnelStats = TunnelStats(),
    val nativeEvents: List<NativeRuntimeEvent> = emptyList(),
    val capturedAt: Long = 0,
) {
    companion object {
        fun idle(source: String): NativeRuntimeSnapshot = NativeRuntimeSnapshot(source = source)
    }
}
