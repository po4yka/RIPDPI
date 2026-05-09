package com.poyka.ripdpi.config.relay

import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.RelayPresetSuggestionUiState
import com.poyka.ripdpi.activities.TransportRemediationKind
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.RelayPresetSuggestion
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
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
