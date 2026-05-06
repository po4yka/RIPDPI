package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ResolverRecommendation
import com.poyka.ripdpi.diagnostics.StrategyProbeReport

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
    fun toUiModel(recommendation: ResolverRecommendation): DiagnosticsResolverRecommendationUiModel =
        DiagnosticsResolverRecommendationUiModel(
            headline = "Switch DNS to ${recommendation.selectedResolverId.replaceFirstChar { it.uppercase() }}",
            rationale = recommendation.rationale,
            fields =
                listOf(
                    DiagnosticsFieldUiModel("Trigger", recommendation.triggerOutcome),
                    DiagnosticsFieldUiModel("Resolver", recommendation.selectedResolverId),
                    DiagnosticsFieldUiModel("Protocol", recommendation.selectedProtocol.uppercase()),
                    DiagnosticsFieldUiModel("Endpoint", recommendation.selectedEndpoint),
                    DiagnosticsFieldUiModel(
                        "Bootstrap",
                        recommendation.selectedBootstrapIps.joinToString().ifBlank { "None" },
                    ),
                ),
            appliedTemporarily = recommendation.appliedTemporarily,
            persistable = recommendation.persistable,
        )
}

internal fun DiagnosticsUiFactorySupport.toResolverRecommendationUiModel(
    recommendation: ResolverRecommendation,
): DiagnosticsResolverRecommendationUiModel =
    ResolverRecommendationPresenter().toUiModel(recommendation = recommendation)
