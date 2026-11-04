package com.poyka.ripdpi.ui.screens.status

import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.screens.diagnostics.state.summary

internal fun buildDiagnosticCard(
    homeDiagnostics: StatusDiagnosticsUiState,
    stringResolver: StringResolver,
): StatusModeCardUiState {
    val latestAudit = homeDiagnostics.latestAudit
    val cancellable =
        homeDiagnostics.analysisRunStatus == StatusDiagnosticsRunUiStatus.STARTING ||
            homeDiagnostics.analysisRunStatus == StatusDiagnosticsRunUiStatus.RUNNING
    return StatusModeCardUiState(
        mode = StatusMode.Diagnostic,
        title = stringResolver.getString(R.string.status_mode_diagnostic_scan),
        primaryLabel =
            when {
                homeDiagnostics.analysisAction.busy && homeDiagnostics.analysisAction.supportingText.isNotBlank() -> {
                    homeDiagnostics.analysisAction.supportingText
                }

                latestAudit != null -> {
                    latestAudit.headline
                }

                else -> {
                    stringResolver.getString(R.string.status_mode_card_diagnostic_empty)
                }
            },
        secondaryLabel = latestAudit?.confidenceLabel(),
        statusLine =
            when {
                homeDiagnostics.analysisAction.busy -> {
                    stringResolver.getString(R.string.status_mode_card_status_busy)
                }

                latestAudit != null -> {
                    latestAudit.diagnosticStatusLine(stringResolver)
                }

                else -> {
                    stringResolver.getString(R.string.status_mode_card_status_inactive)
                }
            },
        primaryActionLabel =
            stringResolver.getString(
                if (cancellable) R.string.diagnostics_action_cancel else R.string.status_diagnostics_quick_scan,
            ),
        configureLabel = stringResolver.getString(R.string.status_diagnostics_open_diagnostics_action),
        primaryActionEnabled =
            cancellable || (homeDiagnostics.analysisAction.enabled && !homeDiagnostics.analysisAction.busy),
        isActive = false,
        isLoading = homeDiagnostics.analysisAction.busy,
        // Only while a run is in flight: the card otherwise shows the last audit, not a pipeline.
        analysisProgress = homeDiagnostics.analysisProgress.takeIf { homeDiagnostics.analysisAction.busy },
    )
}

private fun StatusDiagnosticsLatestAuditUiState.confidenceLabel(): String? =
    recommendationSummary
        ?: summary.takeIf { it.isNotBlank() }

private fun StatusDiagnosticsLatestAuditUiState.diagnosticStatusLine(stringResolver: StringResolver): String =
    when {
        stale -> {
            stringResolver.getString(R.string.status_mode_card_diagnostic_status_stale)
        }

        actionable -> {
            stringResolver.getString(R.string.status_mode_card_diagnostic_status_actionable)
        }

        failedStageCount > 0 && totalStageCount > 0 -> {
            stringResolver.getQuantityString(
                R.plurals.status_mode_card_diagnostic_status_review_format,
                totalStageCount,
                failedStageCount,
                totalStageCount,
            )
        }

        totalStageCount > 0 -> {
            stringResolver.getQuantityString(
                R.plurals.status_mode_card_diagnostic_status_complete_format,
                totalStageCount,
                completedStageCount,
                totalStageCount,
            )
        }

        else -> {
            summary.ifBlank {
                stringResolver.getString(R.string.status_mode_card_diagnostic_status_review)
            }
        }
    }
