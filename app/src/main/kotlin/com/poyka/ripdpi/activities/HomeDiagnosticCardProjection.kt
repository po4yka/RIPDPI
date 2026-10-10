package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.StringResolver

internal fun buildDiagnosticCard(
    homeDiagnostics: HomeDiagnosticsUiState,
    stringResolver: StringResolver,
): HomeModeCardUiState {
    val latestAudit = homeDiagnostics.latestAudit
    val cancellable =
        homeDiagnostics.analysisRunStatus == HomeDiagnosticsRunUiStatus.STARTING ||
            homeDiagnostics.analysisRunStatus == HomeDiagnosticsRunUiStatus.RUNNING
    return HomeModeCardUiState(
        mode = HomeMode.Diagnostic,
        title = stringResolver.getString(R.string.home_mode_diagnostic_scan),
        primaryLabel =
            when {
                homeDiagnostics.analysisAction.busy && homeDiagnostics.analysisAction.supportingText.isNotBlank() -> {
                    homeDiagnostics.analysisAction.supportingText
                }

                latestAudit != null -> {
                    latestAudit.headline
                }

                else -> {
                    stringResolver.getString(R.string.home_mode_card_diagnostic_empty)
                }
            },
        secondaryLabel = latestAudit?.confidenceLabel(),
        statusLine =
            when {
                homeDiagnostics.analysisAction.busy -> {
                    stringResolver.getString(R.string.home_mode_card_status_busy)
                }

                latestAudit != null -> {
                    latestAudit.diagnosticStatusLine(stringResolver)
                }

                else -> {
                    stringResolver.getString(R.string.home_mode_card_status_inactive)
                }
            },
        primaryActionLabel =
            stringResolver.getString(
                if (cancellable) R.string.diagnostics_action_cancel else R.string.home_diagnostics_quick_scan,
            ),
        configureLabel = stringResolver.getString(R.string.home_diagnostics_open_diagnostics_action),
        primaryActionEnabled =
            cancellable || (homeDiagnostics.analysisAction.enabled && !homeDiagnostics.analysisAction.busy),
        isActive = false,
        isLoading = homeDiagnostics.analysisAction.busy,
        // Only while a run is in flight: the card otherwise shows the last audit, not a pipeline.
        analysisProgress = homeDiagnostics.analysisProgress.takeIf { homeDiagnostics.analysisAction.busy },
    )
}

private fun HomeDiagnosticsLatestAuditUiState.confidenceLabel(): String? =
    recommendationSummary
        ?: summary.takeIf { it.isNotBlank() }

private fun HomeDiagnosticsLatestAuditUiState.diagnosticStatusLine(stringResolver: StringResolver): String =
    when {
        stale -> {
            stringResolver.getString(R.string.home_mode_card_diagnostic_status_stale)
        }

        actionable -> {
            stringResolver.getString(R.string.home_mode_card_diagnostic_status_actionable)
        }

        failedStageCount > 0 && totalStageCount > 0 -> {
            stringResolver.getString(
                R.string.home_mode_card_diagnostic_status_review_format,
                failedStageCount,
                totalStageCount,
            )
        }

        totalStageCount > 0 -> {
            stringResolver.getString(
                R.string.home_mode_card_diagnostic_status_complete_format,
                completedStageCount,
                totalStageCount,
            )
        }

        else -> {
            summary.ifBlank {
                stringResolver.getString(R.string.home_mode_card_diagnostic_status_review)
            }
        }
    }
