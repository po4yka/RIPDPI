package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable

internal enum class DiagnosticsOutcomeSource { Active, CompositeAudit, Session, Empty }

/** Metadata stays with the evidence that supplied the conclusion. Null means unknown. */
@Immutable
internal data class DiagnosticsOutcomeUiModel(
    val source: DiagnosticsOutcomeSource,
    val evidenceId: String? = null,
    val headline: String? = null,
    val summary: String? = null,
    val measuredAt: String? = null,
    val confidence: String? = null,
    val coverage: String? = null,
    val completedStages: Int = 0,
    val totalStages: Int = 0,
    val failedStages: Int = 0,
    val stale: Boolean = false,
    val networkMatches: Boolean? = null,
)

internal fun diagnosticsOutcome(
    overview: DiagnosticsOverviewUiModel,
    scan: DiagnosticsScanUiModel,
    home: HomeDiagnosticsUiState,
    active: Boolean,
): DiagnosticsOutcomeUiModel {
    if (active) {
        return DiagnosticsOutcomeUiModel(
            source = DiagnosticsOutcomeSource.Active,
            summary =
                home.analysisAction.supportingText.takeIf { home.analysisAction.busy && it.isNotBlank() }
                    ?: home.verifiedVpnAction.supportingText.takeIf { home.verifiedVpnAction.busy && it.isNotBlank() }
                    ?: scan.activeProgress?.summary,
        )
    }
    home.latestAudit?.takeIf { it.source == HomeDiagnosticsAuditSource.Composite }?.let { audit ->
        return DiagnosticsOutcomeUiModel(
            source = DiagnosticsOutcomeSource.CompositeAudit,
            evidenceId = audit.evidenceId,
            headline = audit.headline,
            summary = audit.summary,
            confidence = audit.confidenceSummary,
            coverage = audit.coverageSummary,
            completedStages = audit.completedStageCount,
            totalStages = audit.totalStageCount,
            failedStages = audit.failedStageCount,
            stale = audit.stale,
            networkMatches = audit.networkMatches,
        )
    }
    overview.latestSession?.let { session ->
        return DiagnosticsOutcomeUiModel(
            source = DiagnosticsOutcomeSource.Session,
            evidenceId = session.id,
            headline = session.summary.ifBlank { session.title },
            summary = listOfNotNull(session.title, session.completionLabel).joinToString(" · "),
            measuredAt = session.startedAtLabel.takeIf(String::isNotBlank),
        )
    }
    return DiagnosticsOutcomeUiModel(source = DiagnosticsOutcomeSource.Empty)
}
