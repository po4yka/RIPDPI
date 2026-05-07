package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayPresetSuggestion
import com.poyka.ripdpi.data.ServerCapabilityObservation
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import java.util.Locale
import com.poyka.ripdpi.data.FailureClass as RuntimeFailureClass

internal fun RelayPresetSuggestion?.toUiState(draft: ConfigDraft): RelayPresetSuggestionUiState? =
    this
        ?.takeUnless { draft.relayPresetId == it.preset.id }
        ?.let { suggestion ->
            RelayPresetSuggestionUiState(
                presetId = suggestion.preset.id,
                title = suggestion.preset.title,
                reason = suggestion.reason,
            )
        }

internal fun resolveRelayPresetSuggestion(
    heuristicSuggestion: RelayPresetSuggestion?,
    serviceTelemetry: ServiceTelemetrySnapshot,
    capabilityRecords: List<ServerCapabilityRecord> = emptyList(),
    transportRemediation: TransportRemediationKind? = null,
): RelayPresetSuggestion? {
    val suggestion = heuristicSuggestion ?: return null
    val transportReason = transportRemediation?.toRelayPresetReason()
    val evidence = relayPresetEvidenceReason(serviceTelemetry)
    return when {
        transportReason != null -> suggestion.copy(reason = transportReason)
        evidence != null -> suggestion.copy(reason = evidence)
        capabilityRecords.isNotEmpty() -> suggestion
        else -> null
    }
}

internal fun TransportRemediationKind.toRelayPresetReason(): String =
    when (this) {
        TransportRemediationKind.OWNED_STACK_ACTION -> {
            "Direct-mode diagnostics report owned-stack only is viable on this network. " +
                "The suggested preset is a remediation hint pending an owned-stack switch."
        }

        TransportRemediationKind.BROWSER_FALLBACK -> {
            "Direct-mode diagnostics flagged transparent-TLS interference on this network. " +
                "Use the suggested preset to favour a browser-camouflage relay path."
        }

        TransportRemediationKind.QUIC_FALLBACK -> {
            "Direct-mode diagnostics report TCP-only direct paths on this network. " +
                "Use the suggested preset to favour a QUIC-capable relay path."
        }

        TransportRemediationKind.NO_RELIABLE_RELAY_HINT -> {
            "Direct-mode diagnostics report no reliable transparent direct path yet. " +
                "Use the suggested preset until further evidence narrows the relay choice."
        }
    }

private fun relayPresetEvidenceReason(serviceTelemetry: ServiceTelemetrySnapshot): String? =
    when {
        serviceTelemetry.status != AppStatus.Running -> {
            null
        }

        serviceTelemetry.hasWhitelistPressureEvidence() -> {
            "Recent runtime diagnostics show whitelist-style routing pressure on this cellular network. " +
                "Use the Russian mobile relay preset to keep domestic traffic direct while shifting " +
                "foreign relay paths."
        }

        serviceTelemetry.hasRelayOrWarpDegradation() -> {
            "Recent relay or WARP control-plane telemetry is degraded on this cellular network. " +
                "Use the Russian mobile relay preset before foreign relay reachability collapses."
        }

        else -> {
            null
        }
    }

private fun ServiceTelemetrySnapshot.hasWhitelistPressureEvidence(): Boolean =
    recentPressureTexts().any { text ->
        text.contains("whitelist_sni") ||
            text.contains("transport_vpn") ||
            text.contains("fingerprint policy") ||
            text.contains("split tunnel")
    } ||
        runtimeFieldTelemetry.failureClass == RuntimeFailureClass.FingerprintPolicy

private fun ServiceTelemetrySnapshot.hasRelayOrWarpDegradation(): Boolean =
    relayTelemetry.isDegradedControlPlane() ||
        warpTelemetry.isDegradedControlPlane() ||
        runtimeFieldTelemetry.failureClass in
        setOf(
            RuntimeFailureClass.TlsInterference,
            RuntimeFailureClass.Timeout,
            RuntimeFailureClass.ResetAbort,
            RuntimeFailureClass.WarpEndpoint,
            RuntimeFailureClass.FingerprintPolicy,
        )

private fun ServiceTelemetrySnapshot.recentPressureTexts(): List<String> =
    listOf(
        proxyTelemetry.lastFailureClass,
        proxyTelemetry.lastError,
        relayTelemetry.lastFailureClass,
        relayTelemetry.lastError,
        warpTelemetry.lastFailureClass,
        warpTelemetry.lastError,
    ).mapNotNull { value ->
        value?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
    }

private fun NativeRuntimeSnapshot.isDegradedControlPlane(): Boolean {
    val healthState = health.trim().lowercase()
    return healthState == "degraded" ||
        healthState == "failed" ||
        lastFailureClass?.isNotBlank() == true ||
        lastError?.isNotBlank() == true
}

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
