package com.poyka.ripdpi.settings.state

import com.poyka.ripdpi.activities.AdaptiveFallbackUiState
import com.poyka.ripdpi.activities.DetectionResistanceUiState
import com.poyka.ripdpi.activities.HttpParserUiState
import com.poyka.ripdpi.activities.QuicUiState
import com.poyka.ripdpi.data.DefaultEntropyPaddingMax
import com.poyka.ripdpi.data.DefaultEntropyPaddingTargetPermil
import com.poyka.ripdpi.data.DefaultEvolutionCooldownAfterFailures
import com.poyka.ripdpi.data.DefaultEvolutionCooldownMs
import com.poyka.ripdpi.data.DefaultEvolutionDecayHalfLifeMs
import com.poyka.ripdpi.data.DefaultEvolutionEpsilon
import com.poyka.ripdpi.data.DefaultEvolutionExperimentTtlMs
import com.poyka.ripdpi.data.DefaultShannonEntropyTargetPermil
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.effectiveQuicFakeHost
import com.poyka.ripdpi.data.effectiveQuicFakeProfile
import com.poyka.ripdpi.data.effectiveQuicInitialMode
import com.poyka.ripdpi.data.effectiveQuicSupportV1
import com.poyka.ripdpi.data.effectiveQuicSupportV2
import com.poyka.ripdpi.data.entropyModeFromProto
import com.poyka.ripdpi.data.normalizeTlsFingerprintProfile
import com.poyka.ripdpi.data.toAdaptiveFallbackSettingsModel
import com.poyka.ripdpi.proto.AppSettings

internal fun AppSettings.buildQuicUiState(): QuicUiState =
    QuicUiState(
        quicInitialMode = effectiveQuicInitialMode(),
        quicSupportV1 = effectiveQuicSupportV1(),
        quicSupportV2 = effectiveQuicSupportV2(),
        quicFakeProfile = effectiveQuicFakeProfile(),
        quicFakeHost = effectiveQuicFakeHost(),
    )

internal fun AppSettings.buildDetectionResistanceUiState(): DetectionResistanceUiState =
    DetectionResistanceUiState(
        quicBindLowPort = quicBindLowPort,
        quicMigrateAfterHandshake = quicMigrateAfterHandshake,
        strategyEvolution = strategyEvolution,
        evolutionEpsilon = evolutionEpsilon.takeIf { it in 0.0..1.0 } ?: DefaultEvolutionEpsilon,
        evolutionExperimentTtlMs = evolutionExperimentTtlMs.takeIf { it > 0 } ?: DefaultEvolutionExperimentTtlMs,
        evolutionDecayHalfLifeMs = evolutionDecayHalfLifeMs.takeIf { it > 0 } ?: DefaultEvolutionDecayHalfLifeMs,
        evolutionCooldownAfterFailures =
            evolutionCooldownAfterFailures.takeIf { it > 0 } ?: DefaultEvolutionCooldownAfterFailures,
        evolutionCooldownMs = evolutionCooldownMs.takeIf { it > 0 } ?: DefaultEvolutionCooldownMs,
        tlsFingerprintProfile = normalizeTlsFingerprintProfile(tlsFingerprintProfile),
        entropyMode = entropyModeFromProto(entropyMode),
        entropyPaddingTargetPermil = entropyPaddingTargetPermil.takeIf { it > 0 } ?: DefaultEntropyPaddingTargetPermil,
        entropyPaddingMax = entropyPaddingMax.takeIf { it > 0 } ?: DefaultEntropyPaddingMax,
        shannonEntropyTargetPermil =
            shannonEntropyTargetPermil.takeIf { it > 0 } ?: DefaultShannonEntropyTargetPermil,
    )

internal fun AppSettings.buildHttpParserUiState(): HttpParserUiState =
    HttpParserUiState(
        hostMixedCase = hostMixedCase,
        domainMixedCase = domainMixedCase,
        hostRemoveSpaces = hostRemoveSpaces,
        httpHostPad = httpHostPad,
        httpMethodEol = httpMethodEol,
        httpUnixEol = httpUnixEol,
        httpMethodSpace = httpMethodSpace,
        httpHostExtraSpace = httpHostExtraSpace,
        httpHostTab = httpHostTab,
    )

internal fun AppSettings.buildAdaptiveFallbackUiState(
    proxyTelemetry: NativeRuntimeSnapshot,
    runtimeOverrideRememberedPolicy: Boolean,
): AdaptiveFallbackUiState {
    val adaptive = toAdaptiveFallbackSettingsModel()
    return AdaptiveFallbackUiState(
        enabled = adaptive.enabled,
        torst = adaptive.torst,
        tlsErr = adaptive.tlsErr,
        httpRedirect = adaptive.httpRedirect,
        connectFailure = adaptive.connectFailure,
        autoSort = adaptive.autoSort,
        cacheTtlSeconds = adaptive.cacheTtlSeconds,
        cachePrefixV4 = adaptive.cachePrefixV4,
        runtimeOverrideActive = proxyTelemetry.adaptiveOverrideActive,
        runtimeOverrideRememberedPolicy = proxyTelemetry.adaptiveOverrideActive && runtimeOverrideRememberedPolicy,
        runtimeRouteGroup = proxyTelemetry.lastRouteGroup,
        runtimeTriggerMask = proxyTelemetry.adaptiveTriggerMask,
        runtimeLastTrigger = proxyTelemetry.adaptiveLastTrigger,
        runtimeOverrideReason = proxyTelemetry.adaptiveOverrideReason,
    )
}
