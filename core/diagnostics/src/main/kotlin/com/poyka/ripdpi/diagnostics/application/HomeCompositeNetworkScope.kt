@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.data.DiagnosticsNetworkEpochProvider
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.diagnostics.application.DiagnosticsNetworkScope
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/** Captures both identity and physical callback evidence at the same run boundary. */
internal class DiagnosticsNetworkScopeFactory
    @Inject
    constructor(
        private val epochProvider: DiagnosticsNetworkEpochProvider,
        private val fingerprintProvider: NetworkFingerprintProvider,
    ) {
        fun capture(): DiagnosticsNetworkScope = DiagnosticsNetworkScope(epochProvider, fingerprintProvider)
    }

internal fun HomeCompositeFinalizationRequest.hasCurrentNetworkScope(): Boolean =
    !networkChanged && networkScope?.isCurrent() == true

internal suspend fun <T> HomeCompositeFinalizationRequest.measureNetwork(measure: suspend () -> T?): T? {
    if (!hasCurrentNetworkScope()) return null
    val result =
        try {
            measure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    return result.takeIf { hasCurrentNetworkScope() }
}

/** Retain historical stage records and local observations without current-network authority. */
internal fun DiagnosticsHomeCompositeOutcome.withoutNetworkAuthority(): DiagnosticsHomeCompositeOutcome {
    val localSignals = detectionDecisionSignals.filter { it.scope != DetectionScope.NETWORK_OBSERVATION.name }
    return copy(
        fingerprintHash = null,
        actionable = false,
        headline = "Network scope unavailable",
        summary =
            "Network changed during analysis or its original scope could not be verified. " +
                "Repeat analysis on a stable network.",
        recommendationSummary = null,
        confidenceSummary = null,
        appliedSettings = emptyList(),
        capabilityEvidence = emptyList(),
        directModeVerdict = null,
        recommendedSessionId = null,
        stageSummaries = stageSummaries.map { it.copy(recommendationContributor = false) },
        detectionVerdict =
            when {
                detectionSignalCount == null -> {
                    null
                }

                DetectionScope.NETWORK_OBSERVATION in detectionEvidenceScopes -> {
                    DiagnosticsHomeDetectionVerdict.NEEDS_REVIEW
                }

                else -> {
                    detectionVerdict
                }
            },
        detectionFindings = detectionLocalFindings,
        detectionRuleApplied = null,
        detectionEvidenceScopes = localSignals.map { DetectionScope.valueOf(it.scope) }.distinct(),
        detectionSignalCount = detectionSignalCount?.let { localSignals.size },
        detectionNetworkFindings = emptyList(),
        detectionDecisionSignals = localSignals,
        networkCharacter = null,
        strategyEffectiveness = emptyList(),
        regressionDelta = null,
        bufferbloat = null,
        dnsCharacterization = null,
        connectivityAssessment = null,
        internetLossReproAction = null,
    )
}
