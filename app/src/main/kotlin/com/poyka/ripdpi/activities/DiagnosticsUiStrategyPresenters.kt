package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ResolverRecommendation
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.diagnostics.buildStrategyProbeCandidateDetails
import com.poyka.ripdpi.ui.diagnostics.buildStrategyProbeReportPresentation
import com.poyka.ripdpi.ui.diagnostics.toStrategyProbeFamilies
import kotlinx.collections.immutable.toImmutableList

internal class StrategyReportSummaryMapper {
    fun summaryMetrics(report: StrategyProbeReport): List<DiagnosticsMetricUiModel> =
        buildStrategyProbeSummaryMetrics(report)

    fun families(report: StrategyProbeReport) = report.toStrategyProbeFamilies()
}

internal class CandidateDetailMapper {
    fun candidateDetails(
        factory: DiagnosticsUiFactorySupport,
        report: StrategyProbeReport,
        reportResults: List<ProbeResult>,
        serviceMode: String?,
    ): Map<String, DiagnosticsStrategyProbeCandidateDetailUiModel> =
        factory.buildStrategyProbeCandidateDetails(
            report = report,
            reportResults = reportResults,
            serviceMode = serviceMode,
        )
}

internal class AuditAssessmentPresenter {
    fun reportPresentation(
        factory: DiagnosticsUiFactorySupport,
        report: StrategyProbeReport,
        winningPath: DiagnosticsStrategyProbeWinningPathUiModel?,
    ): DiagnosticsStrategyProbeReportPresentationUiModel =
        factory.buildStrategyProbeReportPresentation(
            report = report,
            winningPath = winningPath,
        )
}

internal class ResolverRecommendationPresenter {
    fun toUiModel(
        recommendation: ResolverRecommendation,
        stringResolver: StringResolver,
    ): DiagnosticsResolverRecommendationUiModel =
        DiagnosticsResolverRecommendationUiModel(
            headline =
                stringResolver.getString(
                    R.string.diagnostics_resolver_switch_dns,
                    recommendation.selectedResolverId.replaceFirstChar { it.uppercase() },
                ),
            rationale = recommendation.rationale,
            fields =
                listOf(
                    DiagnosticsFieldUiModel(
                        stringResolver.getString(R.string.diagnostics_resolver_field_trigger),
                        recommendation.triggerOutcome,
                    ),
                    DiagnosticsFieldUiModel(
                        stringResolver.getString(R.string.diagnostics_resolver_field_resolver),
                        recommendation.selectedResolverId,
                    ),
                    DiagnosticsFieldUiModel(
                        stringResolver.getString(R.string.diagnostics_resolver_field_protocol),
                        recommendation.selectedProtocol.uppercase(),
                    ),
                    DiagnosticsFieldUiModel(
                        stringResolver.getString(R.string.diagnostics_resolver_field_endpoint),
                        recommendation.selectedEndpoint,
                    ),
                    DiagnosticsFieldUiModel(
                        stringResolver.getString(R.string.diagnostics_resolver_field_bootstrap),
                        recommendation.selectedBootstrapIps.joinToString().ifBlank {
                            stringResolver.getString(R.string.diagnostics_value_none)
                        },
                    ),
                ).toImmutableList(),
            appliedTemporarily = recommendation.appliedTemporarily,
            persistable = recommendation.persistable,
        )
}

internal fun DiagnosticsUiFactorySupport.toResolverRecommendationUiModel(
    recommendation: ResolverRecommendation,
): DiagnosticsResolverRecommendationUiModel =
    ResolverRecommendationPresenter().toUiModel(recommendation = recommendation, stringResolver = context)
