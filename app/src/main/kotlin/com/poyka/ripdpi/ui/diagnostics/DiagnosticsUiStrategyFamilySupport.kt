package com.poyka.ripdpi.ui.diagnostics

import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeFamilyUiModel
import com.poyka.ripdpi.activities.StrategyProbeSuiteFullMatrixV1
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

internal fun StrategyProbeReport.toStrategyProbeFamilies() =
    persistentListOf(
        toStrategyProbeFamily(
            title = if (suiteId == StrategyProbeSuiteFullMatrixV1) "TCP / HTTP / HTTPS matrix" else "TCP candidates",
            candidates = tcpCandidates,
            recommendedId = recommendation?.tcpCandidateId,
        ),
        toStrategyProbeFamily(
            title = if (suiteId == StrategyProbeSuiteFullMatrixV1) "QUIC matrix" else "QUIC candidates",
            candidates = quicCandidates,
            recommendedId = recommendation?.quicCandidateId,
        ),
    )

private fun toStrategyProbeFamily(
    title: String,
    candidates: List<StrategyProbeCandidateSummary>,
    recommendedId: String?,
): DiagnosticsStrategyProbeFamilyUiModel =
    DiagnosticsStrategyProbeFamilyUiModel(
        title = title,
        candidates =
            candidates
                .map { candidate -> candidate.toCandidateUiModel(recommended = candidate.id == recommendedId) }
                .sortedWith(
                    compareByDescending<DiagnosticsStrategyProbeCandidateUiModel> { it.recommended }
                        .thenBy { it.skipped }
                        .thenBy { it.label },
                ).toImmutableList(),
    )
