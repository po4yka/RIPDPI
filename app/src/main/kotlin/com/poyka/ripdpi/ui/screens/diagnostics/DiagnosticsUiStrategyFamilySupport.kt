package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsStrategyProbeCandidateUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsStrategyProbeFamilyUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.StrategyProbeSuiteFullMatrixV1
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

internal fun StrategyProbeReport.toStrategyProbeFamilies(stringResolver: StringResolver) =
    persistentListOf(
        toStrategyProbeFamily(
            title =
                if (suiteId ==
                    StrategyProbeSuiteFullMatrixV1
                ) {
                    stringResolver.getString(R.string.diagnostics_strategy_tcp_matrix)
                } else {
                    stringResolver.getString(R.string.diagnostics_strategy_tcp_candidates)
                },
            stringResolver = stringResolver,
            candidates = tcpCandidates,
            recommendedId = recommendation?.tcpCandidateId,
        ),
        toStrategyProbeFamily(
            title =
                if (suiteId ==
                    StrategyProbeSuiteFullMatrixV1
                ) {
                    stringResolver.getString(R.string.diagnostics_strategy_quic_matrix)
                } else {
                    stringResolver.getString(R.string.diagnostics_strategy_quic_candidates)
                },
            stringResolver = stringResolver,
            candidates = quicCandidates,
            recommendedId = recommendation?.quicCandidateId,
        ),
    )

private fun toStrategyProbeFamily(
    title: String,
    stringResolver: StringResolver,
    candidates: List<StrategyProbeCandidateSummary>,
    recommendedId: String?,
): DiagnosticsStrategyProbeFamilyUiModel =
    DiagnosticsStrategyProbeFamilyUiModel(
        title = title,
        candidates =
            candidates
                .map { candidate ->
                    candidate.toCandidateUiModel(
                        recommended = candidate.id == recommendedId,
                        stringResolver = stringResolver,
                    )
                }.sortedWith(
                    compareByDescending<DiagnosticsStrategyProbeCandidateUiModel> { it.recommended }
                        .thenBy { it.skipped }
                        .thenBy { it.label },
                ).toImmutableList(),
    )
