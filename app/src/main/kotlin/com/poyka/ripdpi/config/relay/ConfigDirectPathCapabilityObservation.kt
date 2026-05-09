package com.poyka.ripdpi.config.relay

import com.poyka.ripdpi.data.ServerCapabilityObservation
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import java.util.Locale

internal fun buildDirectPathCapabilityObservation(
    telemetry: ServiceTelemetrySnapshot,
): Pair<String, ServerCapabilityObservation>? {
    val proxyTelemetry = telemetry.proxyTelemetry
    val authority =
        proxyTelemetry.lastTarget?.trim()?.takeIf { it.isNotEmpty() }
            ?: proxyTelemetry.lastHost?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
    val healthState = proxyTelemetry.health.trim().lowercase(Locale.US)
    val healthy = proxyTelemetry.state.equals("running", ignoreCase = true) || healthState == "healthy"
    return authority to
        ServerCapabilityObservation(
            quicUsable =
                when {
                    proxyTelemetry.protocolKind?.contains("quic", ignoreCase = true) == true && healthy -> true
                    proxyTelemetry.lastFailureClass?.contains("quic", ignoreCase = true) == true -> false
                    else -> null
                },
            udpUsable = proxyTelemetry.udpCapable,
            multiplexReusable = if (healthy && proxyTelemetry.totalSessions > 1) true else null,
            fallbackRequired =
                proxyTelemetry.lastFallbackAction?.trim()?.takeIf { it.isNotEmpty() } != null ||
                    proxyTelemetry.fallbackMode?.trim()?.takeIf { it.isNotEmpty() } != null,
            repeatedHandshakeFailureClass =
                proxyTelemetry.lastFailureClass?.trim()?.takeIf { it.isNotEmpty() }
                    ?: proxyTelemetry.lastError?.trim()?.takeIf { it.isNotEmpty() },
        )
}
