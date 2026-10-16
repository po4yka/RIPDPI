package com.poyka.ripdpi.ui.screens.simple

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomeDiagnosticsRunUiStatus
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun SimpleAnalysisChanges(diagnostics: HomeDiagnosticsUiState) {
    val terminal =
        diagnostics.analysisRunStatus == HomeDiagnosticsRunUiStatus.COMPLETED ||
            diagnostics.analysisRunStatus == HomeDiagnosticsRunUiStatus.FAILED
    val sheet = diagnostics.analysisSheet
    if (terminal && sheet != null && sheet.appliedSettings.isNotEmpty()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                modifier = Modifier.semantics { heading() },
                text = stringResource(R.string.home_diagnostics_applied_settings_label),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
            )
            sheet.appliedSettings.forEach { setting ->
                SettingsRow(title = setting.label, value = setting.value)
            }
        }
    }
    if (diagnostics.analysisRunStatus == HomeDiagnosticsRunUiStatus.CANCELLED || (terminal && sheet == null)) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(R.string.simple_analysis_saved_changes_body),
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
            textAlign = TextAlign.Center,
        )
    }
}
