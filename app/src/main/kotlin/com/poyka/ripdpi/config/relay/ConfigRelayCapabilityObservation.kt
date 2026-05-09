package com.poyka.ripdpi.config.relay

import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.defaultRelayPort
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.ServerCapabilityObservation
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import java.util.Locale

@Suppress("CyclomaticComplexMethod")
internal fun buildRelayCapabilityObservation(
    draft: ConfigDraft,
    telemetry: ServiceTelemetrySnapshot,
): Pair<String, ServerCapabilityObservation>? {
    val relayTelemetry = telemetry.relayTelemetry
    val authority = relayAuthorityCandidate(draft, relayTelemetry) ?: return null
    val healthState = relayTelemetry.health.trim().lowercase(Locale.US)
    val lastFailureText =
        relayTelemetry.lastFailureClass?.trim()?.takeIf { it.isNotEmpty() }
            ?: relayTelemetry.lastHandshakeError?.trim()?.takeIf { it.isNotEmpty() }
            ?: relayTelemetry.lastError?.trim()?.takeIf { it.isNotEmpty() }
    val successfulSession = relayTelemetry.activeSessions > 0 || relayTelemetry.totalSessions > 0
    val quicRelayKind =
        draft.relayKind == RelayKindTuicV5 ||
            draft.relayKind == RelayKindHysteria2 ||
            draft.relayKind == RelayKindMasque
    val healthy = relayTelemetry.state.equals("running", ignoreCase = true) || healthState == "healthy"
    return authority to
        ServerCapabilityObservation(
            quicUsable =
                when {
                    quicRelayKind && healthy -> true
                    quicRelayKind && relayTelemetry.lastFailureClass?.isNotBlank() == true -> false
                    else -> null
                },
            udpUsable =
                relayTelemetry.udpCapable ?: when {
                    quicRelayKind && healthy -> true
                    quicRelayKind && relayTelemetry.lastFailureClass?.isNotBlank() == true -> false
                    else -> null
                },
            authModeAccepted = if (successfulSession && healthy) true else null,
            multiplexReusable = if (successfulSession && relayTelemetry.routeChanges == 0L) true else null,
            shadowTlsCamouflageAccepted =
                when {
                    draft.relayKind == RelayKindShadowTlsV3 && healthy -> true

                    draft.relayKind == RelayKindShadowTlsV3 &&
                        relayTelemetry.lastFailureClass?.isNotBlank() == true -> false

                    else -> null
                },
            naiveHttpsProxyAccepted =
                when {
                    draft.relayKind == RelayKindNaiveProxy && healthy -> true

                    draft.relayKind == RelayKindNaiveProxy &&
                        relayTelemetry.lastFailureClass?.isNotBlank() == true -> false

                    else -> null
                },
            fallbackRequired =
                relayTelemetry.lastFallbackAction?.trim()?.takeIf { it.isNotEmpty() } != null ||
                    relayTelemetry.fallbackMode?.trim()?.takeIf { it.isNotEmpty() } != null,
            repeatedHandshakeFailureClass = lastFailureText,
        )
}

private fun relayAuthorityCandidate(
    draft: ConfigDraft,
    relayTelemetry: NativeRuntimeSnapshot,
): String? =
    relayTelemetry.upstreamAddress?.trim()?.takeIf { it.isNotEmpty() }
        ?: relayTelemetry.lastTarget?.trim()?.takeIf { it.isNotEmpty() }
        ?: relayTelemetry.lastHost?.trim()?.takeIf { it.isNotEmpty() }
        ?: when {
            draft.relayMasqueUrl.isNotBlank() -> draft.relayMasqueUrl

            draft.relayServer.isNotBlank() -> "${draft.relayServer}:${draft.relayServerPort.ifBlank {
                defaultRelayPort
                    .toString()
            }}"

            else -> null
        }
