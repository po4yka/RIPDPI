package com.poyka.ripdpi.activities

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultRelayLocalSocksPort
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.DefaultSnowflakeBrokerUrl
import com.poyka.ripdpi.data.DefaultSnowflakeFrontDomain
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.RelayCloudflareTunnelModeConsumeExisting
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.data.RelayCongestionControlBbr
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayFinalmaskTypeFragment
import com.poyka.ripdpi.data.RelayFinalmaskTypeHeaderCustom
import com.poyka.ripdpi.data.RelayFinalmaskTypeNoise
import com.poyka.ripdpi.data.RelayFinalmaskTypeOff
import com.poyka.ripdpi.data.RelayFinalmaskTypeSudoku
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindOff
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayMasqueAuthModePreshared
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayPresetCatalog
import com.poyka.ripdpi.data.RelayPresetDefinition
import com.poyka.ripdpi.data.RelayPresetSuggestion
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.ServerCapabilityObservation
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.StrategyChainSet
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.formatChainSummary
import com.poyka.ripdpi.data.formatStrategyChainDsl
import com.poyka.ripdpi.data.normalizeRelayCloudflareTunnelMode
import com.poyka.ripdpi.data.normalizeRelayCongestionControl
import com.poyka.ripdpi.data.normalizeRelayFinalmaskType
import com.poyka.ripdpi.data.normalizeRelayMasqueAuthMode
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.primaryDesyncMethod
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.data.toRelaySettingsModel
import com.poyka.ripdpi.data.validateStrategyChainUsage
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.security.ImportedMasqueClientIdentity
import com.poyka.ripdpi.security.MasqueClientCredentialImporter
import com.poyka.ripdpi.services.MasquePrivacyPassAvailability
import com.poyka.ripdpi.services.MasquePrivacyPassBuildStatus
import com.poyka.ripdpi.utility.checkIp
import com.poyka.ripdpi.utility.validateIntRange
import com.poyka.ripdpi.utility.validatePort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import com.poyka.ripdpi.data.FailureClass as RuntimeFailureClass

internal fun ConfigDraft.applyRelayPresetDefinition(preset: RelayPresetDefinition): ConfigDraft =
    copy(
        relayEnabled = true,
        relayKind = preset.relayKind,
        relayPresetId = preset.id,
        relayChainEntryProfileId =
            if (preset.relayKind == RelayKindChainRelay) {
                preset.chainEntryProfileId
            } else {
                ""
            },
        relayChainExitProfileId =
            if (preset.relayKind == RelayKindChainRelay) {
                preset.chainExitProfileId
            } else {
                ""
            },
        relayChainEntryServer = "",
        relayChainEntryPort = defaultRelayPort.toString(),
        relayChainEntryServerName = "",
        relayChainEntryPublicKey = "",
        relayChainEntryShortId = "",
        relayChainEntryUuid = "",
        relayChainExitServer = "",
        relayChainExitPort = defaultRelayPort.toString(),
        relayChainExitServerName = "",
        relayChainExitPublicKey = "",
        relayChainExitShortId = "",
        relayChainExitUuid = "",
        relayShadowTlsInnerProfileId =
            if (preset.relayKind == RelayKindShadowTlsV3) {
                preset.shadowTlsInnerProfileId
            } else {
                ""
            },
        relayTuicZeroRtt = if (preset.relayKind == RelayKindTuicV5) preset.tuicZeroRtt else relayTuicZeroRtt,
        relayTuicCongestionControl =
            if (preset.relayKind == RelayKindTuicV5) {
                normalizeRelayCongestionControl(preset.tuicCongestionControl)
            } else {
                relayTuicCongestionControl
            },
        relayNaivePath = if (preset.relayKind == RelayKindNaiveProxy) preset.naivePath else "",
        relayUdpEnabled =
            preset.udpEnabled &&
                (
                    preset.relayKind == RelayKindHysteria2 || preset.relayKind == RelayKindMasque ||
                        preset.relayKind == RelayKindTuicV5
                ),
    )

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
): RelayPresetSuggestion? {
    val suggestion = heuristicSuggestion ?: return null
    val evidence = relayPresetEvidenceReason(serviceTelemetry)
    return when {
        evidence != null -> suggestion.copy(reason = evidence)
        capabilityRecords.isNotEmpty() -> suggestion
        else -> null
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

internal fun ConfigDraft.supportsUdpRelay(): Boolean =
    relayKind == RelayKindHysteria2 || relayKind == RelayKindMasque || relayKind == RelayKindTuicV5

internal fun ConfigDraft.supportsFinalmask(): Boolean =
    relayKind == RelayKindVlessReality || relayKind == RelayKindCloudflareTunnel

internal fun validateHeaderCustomFinalmaskDraft(draft: ConfigDraft): String? =
    when {
        draft.relayFinalmaskRandRange.isNotBlank() &&
            !draft.relayFinalmaskRandRange.matches(Regex("\\d+-\\d+")) -> "invalid_range"

        draft.relayFinalmaskHeaderHex.isBlank() && draft.relayFinalmaskTrailerHex.isBlank() -> "required"

        else -> null
    }

internal fun validateNoiseFinalmaskDraft(draft: ConfigDraft): String? =
    when {
        draft.relayFinalmaskRandRange.isBlank() -> {
            "required"
        }

        !draft.relayFinalmaskRandRange.matches(Regex("\\d+-\\d+")) -> {
            "invalid_range"
        }

        else -> {
            val (min, max) =
                draft.relayFinalmaskRandRange
                    .split('-', limit = 2)
                    .mapNotNull { it.toIntOrNull() }
                    .let { values ->
                        if (values.size == 2) values[0] to values[1] else return "invalid_range"
                    }
            if (min > max) "invalid_range" else null
        }
    }

internal fun validateFragmentFinalmaskDraft(draft: ConfigDraft): String? =
    if (
        !validateIntRange(
            draft.relayFinalmaskFragmentPackets,
            relayFinalmaskFragmentPacketsMin,
            relayFinalmaskFragmentPacketsMax,
        ) ||
        !validateIntRange(
            draft.relayFinalmaskFragmentMinBytes,
            relayFinalmaskFragmentBytesMin,
            relayFinalmaskFragmentBytesMax,
        ) ||
        !validateIntRange(
            draft.relayFinalmaskFragmentMaxBytes,
            relayFinalmaskFragmentBytesMin,
            relayFinalmaskFragmentBytesMax,
        )
    ) {
        "out_of_range"
    } else {
        null
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
