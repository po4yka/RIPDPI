package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsEventUiModel
import com.poyka.ripdpi.activities.foldIdentical
import com.poyka.ripdpi.diagnostics.exit.ProcessExitCorrelationKind
import com.poyka.ripdpi.diagnostics.exit.processExitCorrelationKind

/**
 * The watch list as the current mode shows it. Guided mode shows several raw events with the same text (for
 * example, every unexpected exit reads "closed unexpectedly"), so it folds the rows that it shows the same.
 */
@Composable
internal fun diagnosticWarningsPresentation(
    warnings: List<DiagnosticsEventUiModel>,
    expertMode: Boolean,
): List<DiagnosticsEventUiModel> {
    if (expertMode) return warnings
    return warnings.map { guidedWarningPresentation(it) }.foldIdentical()
}

@Composable
private fun guidedWarningPresentation(warning: DiagnosticsEventUiModel): DiagnosticsEventUiModel =
    processExitPresentation(warning) ?: dnsIntegrityPresentation(warning)

@Composable
private fun processExitPresentation(warning: DiagnosticsEventUiModel): DiagnosticsEventUiModel? {
    // An ordinary stop (ExpectedLifecycle) never reaches the watch list; see toWatchList.
    val summary =
        when (processExitCorrelationKind(warning.message)) {
            ProcessExitCorrelationKind.ResourcePressure -> R.string.diagnostics_watch_list_exit_pressure
            ProcessExitCorrelationKind.Unexpected -> R.string.diagnostics_watch_list_exit_unexpected
            ProcessExitCorrelationKind.ExpectedLifecycle, null -> return null
        }
    return warning.copy(
        source = stringResource(R.string.diagnostics_watch_list_exit_title),
        message = stringResource(summary),
    )
}

@Composable
private fun dnsIntegrityPresentation(warning: DiagnosticsEventUiModel): DiagnosticsEventUiModel {
    if (!warning.source.equals("dns_integrity", ignoreCase = true)) return warning
    val outcome = warning.message.substringAfterLast(": ")
    val message =
        if (outcome == "dns_oracle_unavailable") {
            warning.message.removeSuffix(outcome) + stringResource(R.string.diagnostics_dns_encrypted_unavailable)
        } else {
            warning.message
        }
    return warning.copy(
        source = stringResource(R.string.diagnostics_tool_dns_integrity_title),
        message = message,
    )
}
