package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.activities.StrategyProbeSuiteFullMatrixV1
import com.poyka.ripdpi.diagnostics.BypassApproachDetail
import com.poyka.ripdpi.diagnostics.BypassApproachId
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.BypassApproachVerificationState
import com.poyka.ripdpi.diagnostics.BypassRuntimeHealthSummary
import com.poyka.ripdpi.diagnostics.StrategyEmitterTier
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeRecommendation
import com.poyka.ripdpi.diagnostics.StrategyProbeReport

internal fun persianRenderStrategyReport(): StrategyProbeReport =
    StrategyProbeReport(
        suiteId = StrategyProbeSuiteFullMatrixV1,
        tcpCandidates = listOf(persianRenderCandidate("tcp-0012", "User TCP 0012", "tlsrec_seqovl")),
        quicCandidates = listOf(persianRenderCandidate("quic-0012", "User QUIC 0012", "quic_disabled")),
        recommendation =
            StrategyProbeRecommendation(
                tcpCandidateId = "tcp-0012",
                tcpCandidateLabel = "User TCP 0012",
                tcpCandidateFamily = "tlsrec_seqovl",
                quicCandidateId = "quic-0012",
                quicCandidateLabel = "User QUIC 0012",
                quicCandidateFamily = "quic_disabled",
                dnsStrategyLabel = "Plain DNS",
                rationale = "User recommendation 0012",
                recommendedProxyConfigJson = "{}",
                tlsPathSuppressionSummary = "User suppression 0012",
            ),
    )

internal fun persianRenderApproach(): BypassApproachDetail =
    BypassApproachDetail(
        summary =
            BypassApproachSummary(
                approachId = BypassApproachId(BypassApproachKind.Strategy, "strategy-0012"),
                displayName = "User strategy 0012",
                secondaryLabel = "User subtitle 0012",
                verificationState = BypassApproachVerificationState.NOT_EVALUATED,
                validatedScanCount = 0,
                validatedSuccessCount = 0,
                validatedSuccessRate = null,
                lastValidatedResult = null,
                usageCount = 2,
                totalRuntimeDurationMs = 60_000L,
                recentRuntimeHealth = BypassRuntimeHealthSummary(totalErrors = 11, routeChanges = 13),
                lastUsedAt = null,
            ),
    )

private fun persianRenderCandidate(
    id: String,
    label: String,
    family: String,
): StrategyProbeCandidateSummary =
    StrategyProbeCandidateSummary(
        id = id,
        label = label,
        family = family,
        outcome = "success",
        rationale = "User rationale 0012",
        succeededTargets = 1,
        totalTargets = 2,
        weightedSuccessScore = 10,
        totalWeight = 20,
        qualityScore = 7,
        averageLatencyMs = 17,
        emitterTier = StrategyEmitterTier.ROOTED_PRODUCTION,
        exactEmitterRequiresRoot = true,
        notes = listOf("User note 0012"),
    )
