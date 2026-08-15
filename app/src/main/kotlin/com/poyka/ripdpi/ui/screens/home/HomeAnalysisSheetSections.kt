package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomeDiagnosticsAnalysisSheetUiState
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val sheetStageContainerAlpha = 0.06f
private const val sheetDiagnosticsDetectorsShown = 5

@Composable
internal fun HomeAnalysisSheetEvidenceContent(sheet: HomeDiagnosticsAnalysisSheetUiState) {
    HomeCapabilityEvidenceSection(sheet)
    HomeDetectionSummarySection(sheet)
    HomeVpnDetectorsSection(sheet)
}

@Composable
private fun HomeCapabilityEvidenceSection(sheet: HomeDiagnosticsAnalysisSheetUiState) {
    val colors = RipDpiThemeTokens.colors
    if (sheet.capabilityEvidence.isEmpty()) return
    HorizontalDivider(color = colors.divider)
    Text(
        text = stringResource(R.string.home_diagnostics_capability_evidence_label),
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = colors.foreground,
    )
    HomeAnalysisInsetColumn(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
        sheet.capabilityEvidence.forEach { evidence ->
            Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
                Text(text = evidence.authority, style = RipDpiThemeTokens.type.bodyEmphasis, color = colors.foreground)
                Text(
                    text = evidence.summary,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.mutedForeground,
                )
                evidence.fields.forEach { field ->
                    SettingsRow(title = field.label, value = field.value)
                }
            }
        }
    }
}

@Composable
private fun HomeDetectionSummarySection(sheet: HomeDiagnosticsAnalysisSheetUiState) {
    val colors = RipDpiThemeTokens.colors
    if (sheet.detectionVerdict == null) return
    HorizontalDivider(color = colors.divider)
    Text(
        text = stringResource(R.string.home_diagnostics_detection_section_label),
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = colors.foreground,
    )
    HomeAnalysisInsetColumn(modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HomeDiagnosticsDetectionSummary)) {
        Text(text = sheet.detectionVerdict, style = RipDpiThemeTokens.type.bodyEmphasis, color = colors.foreground)
        if (sheet.detectionLocalFindings.isNotEmpty()) {
            Text(
                text = stringResource(R.string.home_diagnostics_detection_local_artifacts),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = colors.foreground,
            )
            sheet.detectionLocalFindings.forEach { finding ->
                Text(text = "• $finding", style = RipDpiThemeTokens.type.secondaryBody, color = colors.mutedForeground)
            }
        }
        if (sheet.detectionNetworkFindings.isNotEmpty()) {
            Text(
                text = stringResource(R.string.home_diagnostics_detection_network_signs),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = colors.foreground,
            )
            sheet.detectionNetworkFindings.forEach { finding ->
                Text(text = "• $finding", style = RipDpiThemeTokens.type.secondaryBody, color = colors.mutedForeground)
            }
        }
        if (sheet.detectionLocalFindings.isEmpty() && sheet.detectionNetworkFindings.isEmpty()) {
            sheet.detectionFindings.forEach { finding ->
                Text(text = "• $finding", style = RipDpiThemeTokens.type.secondaryBody, color = colors.mutedForeground)
            }
        }
    }
}

@Composable
private fun HomeVpnDetectorsSection(sheet: HomeDiagnosticsAnalysisSheetUiState) {
    val colors = RipDpiThemeTokens.colors
    val count = sheet.installedVpnDetectorCount ?: return
    HorizontalDivider(color = colors.divider)
    Text(
        text = stringResource(R.string.home_diagnostics_vpn_detectors_title),
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = colors.foreground,
    )
    HomeAnalysisInsetColumn(modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HomeDiagnosticsVpnDetectorsCard)) {
        Text(
            text = stringResource(R.string.home_diagnostics_vpn_detectors_summary, count),
            style = RipDpiThemeTokens.type.body,
            color = colors.foreground,
        )
        sheet.installedVpnDetectorTopApps.take(sheetDiagnosticsDetectorsShown).forEach { pkg ->
            Text(text = pkg, style = RipDpiThemeTokens.type.secondaryBody, color = colors.mutedForeground)
        }
    }
}

@Composable
internal fun HomeAnalysisSheetNetworkContent(sheet: HomeDiagnosticsAnalysisSheetUiState) {
    HomeNetworkCharacterSection(sheet)
    HomePathCharacterizationSection(sheet)
    HomeStrategyEffectivenessSection(sheet)
    HomeRoutingSanitySection(sheet)
}

@Composable
private fun HomeNetworkCharacterSection(sheet: HomeDiagnosticsAnalysisSheetUiState) {
    val colors = RipDpiThemeTokens.colors
    if (sheet.networkCharacterRows.isEmpty() && sheet.networkCharacterNotes.isEmpty()) return
    HorizontalDivider(color = colors.divider)
    Text(
        text = stringResource(R.string.home_diagnostics_network_section),
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = colors.foreground,
    )
    HomeAnalysisInsetColumn {
        sheet.networkCharacterRows.forEach { row ->
            SettingsRow(title = row.label, value = row.value)
        }
        sheet.networkCharacterNotes.forEach { note ->
            Text(text = "• $note", style = RipDpiThemeTokens.type.secondaryBody, color = colors.mutedForeground)
        }
    }
}

@Composable
private fun HomePathCharacterizationSection(sheet: HomeDiagnosticsAnalysisSheetUiState) {
    val colors = RipDpiThemeTokens.colors
    if (sheet.bufferbloatSummary == null && sheet.dnsCharacterizationSummary == null) return
    HorizontalDivider(color = colors.divider)
    HomeAnalysisInsetColumn {
        sheet.bufferbloatSummary?.let { bloat ->
            Text(
                text = stringResource(R.string.home_diagnostics_bufferbloat_section),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(text = bloat, style = RipDpiThemeTokens.type.body, color = colors.foreground)
        }
        sheet.dnsCharacterizationSummary?.let { dns ->
            Text(
                text = stringResource(R.string.home_diagnostics_dns_section),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(text = dns, style = RipDpiThemeTokens.type.body, color = colors.foreground)
            sheet.dnsCharacterizationNotes.forEach { note ->
                Text(text = "• $note", style = RipDpiThemeTokens.type.secondaryBody, color = colors.mutedForeground)
            }
        }
    }
}

@Composable
private fun HomeStrategyEffectivenessSection(sheet: HomeDiagnosticsAnalysisSheetUiState) {
    val colors = RipDpiThemeTokens.colors
    if (sheet.strategyEffectivenessRows.isEmpty()) return
    HorizontalDivider(color = colors.divider)
    Text(
        text = stringResource(R.string.home_diagnostics_effectiveness_section),
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = colors.foreground,
    )
    HomeAnalysisInsetColumn {
        sheet.strategyEffectivenessRows.forEach { row ->
            SettingsRow(title = row.label, value = row.value)
        }
    }
}

@Composable
private fun HomeRoutingSanitySection(sheet: HomeDiagnosticsAnalysisSheetUiState) {
    val colors = RipDpiThemeTokens.colors
    if (sheet.routingSanitySummary == null && sheet.routingSanityFindings.isEmpty()) return
    HorizontalDivider(color = colors.divider)
    Text(
        text = stringResource(R.string.home_diagnostics_routing_sanity_section),
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = colors.foreground,
    )
    HomeAnalysisInsetColumn {
        sheet.routingSanitySummary?.let { summary ->
            Text(text = summary, style = RipDpiThemeTokens.type.body, color = colors.foreground)
        }
        sheet.routingSanityFindings.forEach { row ->
            SettingsRow(title = row.label, value = row.value)
        }
    }
}

@Composable
internal fun HomeAnalysisSheetResultContent(sheet: HomeDiagnosticsAnalysisSheetUiState) {
    HomeRegressionDeltaSection(sheet)
    HomeStageResultsSection(sheet)
}

@Composable
private fun HomeRegressionDeltaSection(sheet: HomeDiagnosticsAnalysisSheetUiState) {
    val colors = RipDpiThemeTokens.colors
    val summary = sheet.regressionDeltaSummary ?: return
    HorizontalDivider(color = colors.divider)
    Text(
        text = stringResource(R.string.home_diagnostics_regression_section),
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = colors.foreground,
    )
    HomeAnalysisInsetColumn {
        Text(text = summary, style = RipDpiThemeTokens.type.body, color = colors.foreground)
        if (sheet.regressionDeltaFailures.isNotEmpty()) {
            Text(
                text =
                    stringResource(R.string.home_diagnostics_regression_failed_label) +
                        ": " + sheet.regressionDeltaFailures.joinToString(", "),
                style = RipDpiThemeTokens.type.secondaryBody,
                color = colors.destructive,
            )
        }
        if (sheet.regressionDeltaRecoveries.isNotEmpty()) {
            Text(
                text =
                    stringResource(R.string.home_diagnostics_regression_recovered_label) +
                        ": " + sheet.regressionDeltaRecoveries.joinToString(", "),
                style = RipDpiThemeTokens.type.secondaryBody,
                color = colors.success,
            )
        }
    }
}

@Composable
private fun HomeStageResultsSection(sheet: HomeDiagnosticsAnalysisSheetUiState) {
    val colors = RipDpiThemeTokens.colors
    if (sheet.stageSummaries.isEmpty()) return
    HorizontalDivider(color = colors.divider)
    Text(
        text = stringResource(R.string.home_diagnostics_stage_results_label),
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = colors.foreground,
    )
    HomeAnalysisInsetColumn {
        sheet.stageSummaries.forEach { stage ->
            StageResultRow(
                label = stage.label,
                summary = stage.summary,
                failed = stage.failed,
                skipped = stage.skipped,
                recommendationContributor = stage.recommendationContributor,
            )
        }
    }
}

@Composable
private fun HomeAnalysisInsetColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    content: @Composable () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(modifier)
                .background(colors.muted.copy(alpha = sheetStageContainerAlpha), RipDpiThemeTokens.shapes.lg)
                .padding(RipDpiThemeTokens.spacing.sm),
        verticalArrangement = verticalArrangement,
    ) {
        content()
    }
}
