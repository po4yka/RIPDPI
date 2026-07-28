package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NativeRuntimeTelemetrySchemaVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun Json.decodeNativeRuntimeSnapshot(payload: String): NativeRuntimeSnapshot =
    decodeFromString(NativeRuntimeSnapshot.serializer(), payload).also { snapshot ->
        require(snapshot.schemaVersion == NativeRuntimeTelemetrySchemaVersion) {
            "Unsupported native runtime telemetry schema version: ${snapshot.schemaVersion}"
        }
    }

/**
 * Dedicated proxy forwarding evidence pulled from native with a separate JNI
 * getter. The payload is privacy-safe by construction: it carries cumulative
 * counts and epoch timestamps only, never fd, endpoint, IP, host, profile, or
 * raw error details.
 */
@Serializable
data class ProxyForwardingEvidence(
    val proxyClientSocketsAccepted: Long = 0,
    val upstreamSocketCreated: Long = 0,
    val upstreamOpened: Long = 0,
    val upstreamOpenFailures: Long = 0,
    val protectAttempted: Long = 0,
    val protectSucceeded: Long = 0,
    val protectRejected: Long = 0,
    val protectErrors: Long = 0,
    val upstreamApplicationBytes: Long = 0,
    val firstUpstreamApplicationForwardedAt: Long? = null,
    val lastUpstreamApplicationForwardedAt: Long? = null,
) {
    companion object {
        val Empty = ProxyForwardingEvidence()
    }
}

internal fun Json.decodeProxyForwardingEvidence(payload: String): ProxyForwardingEvidence =
    decodeFromString(ProxyForwardingEvidence.serializer(), payload)
