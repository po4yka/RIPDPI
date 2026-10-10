package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsEventUiModel

@Composable
internal fun diagnosticWarningPresentation(
    warning: DiagnosticsEventUiModel,
    expertMode: Boolean,
): DiagnosticsEventUiModel {
    if (expertMode || !warning.source.equals("dns_integrity", ignoreCase = true)) return warning
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
