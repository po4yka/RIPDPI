package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateDetailUiModel
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsVerificationSheetUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

internal fun DiagnosticsScanUiModel.recommendedCandidate(): DiagnosticsStrategyProbeCandidateDetailUiModel? {
    val report = strategyProbeReport ?: return null
    return report.winningPath
        ?.tcpWinner
        ?.id
        ?.let(report.candidateDetails::get)
}

@Composable
internal fun DiagnosticsExpertToggle(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    RipDpiChip(
        text = stringResource(R.string.persona_advanced),
        selected = expanded,
        role = Role.Button,
        onClick = { onExpandedChange(!expanded) },
        modifier =
            Modifier
                .padding(horizontal = RipDpiThemeTokens.layout.horizontalPadding)
                .ripDpiTestTag(RipDpiTestTags.DiagnosticsExpertToggle),
    )
}

@Composable
internal fun DiagnosticsGuidedTools(
    scan: DiagnosticsScanUiModel,
    busy: Boolean,
    onSelectProfile: (String) -> Unit,
    onSelectSection: (DiagnosticsSection) -> Unit,
    onExpand: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(RipDpiThemeTokens.layout.horizontalPadding),
        verticalArrangement =
            androidx.compose.foundation.layout.Arrangement
                .spacedBy(RipDpiThemeTokens.spacing.md),
    ) {
        val guidedProfiles =
            scan.profiles.filter {
                it.id == "selective-availability-matrix" || it.id == "ru-throttling"
            }
        guidedProfiles.forEach { profile ->
            RipDpiButton(
                text = profile.name,
                onClick = {
                    onSelectProfile(profile.id)
                    onSelectSection(DiagnosticsSection.Scan)
                },
                enabled = !busy,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        RipDpiButton(
            text = stringResource(R.string.persona_advanced),
            onClick = onExpand,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun DiagnosticsGuidedActions(
    state: HomeDiagnosticsUiState,
    onRecheck: () -> Unit,
    onStartVerifiedVpn: () -> Unit,
) {
    val audit = state.latestAudit ?: return
    var confirmStart by rememberSaveable { mutableStateOf(false) }
    val colors = RipDpiThemeTokens.colors
    Column(
        verticalArrangement =
            androidx.compose.foundation.layout.Arrangement
                .spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        Text(audit.headline, style = RipDpiThemeTokens.type.bodyEmphasis, color = colors.foreground)
        Text(audit.summary, style = RipDpiThemeTokens.type.secondaryBody, color = colors.mutedForeground)
        audit.comparisonSummary?.let {
            Text(it, style = RipDpiThemeTokens.type.secondaryBody, color = colors.mutedForeground)
        }
        audit.recommendationSummary?.let {
            Text(it, style = RipDpiThemeTokens.type.body, color = colors.foreground)
        }
        RipDpiButton(
            text = state.verifiedVpnAction.label,
            onClick = { confirmStart = true },
            enabled = state.verifiedVpnAction.enabled,
            loading = state.verifiedVpnAction.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            state.verifiedVpnAction.supportingText,
            style = RipDpiThemeTokens.type.caption,
            color = colors.mutedForeground,
        )
        RipDpiButton(
            text = stringResource(R.string.diagnostics_guided_recheck),
            onClick = onRecheck,
            enabled = state.analysisAction.enabled && !state.analysisAction.busy,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (confirmStart) {
        RipDpiDialog(
            title = stringResource(R.string.diagnostics_guided_verify_title),
            onDismissRequest = { confirmStart = false },
            visuals = RipDpiDialogVisuals(message = stringResource(R.string.diagnostics_guided_verify_body)),
            dismissAction =
                RipDpiDialogAction(
                    stringResource(R.string.biometric_dialog_cancel),
                    { confirmStart = false },
                ),
            confirmAction =
                if (state.verifiedVpnAction.enabled) {
                    RipDpiDialogAction(stringResource(R.string.diagnostics_guided_verify_confirm), {
                        confirmStart = false
                        onStartVerifiedVpn()
                    })
                } else {
                    null
                },
        )
    }
}

@Composable
internal fun DiagnosticsVerificationResult(
    result: HomeDiagnosticsVerificationSheetUiState?,
    onDismiss: () -> Unit,
) {
    result ?: return
    RipDpiDialog(
        title = result.headline,
        onDismissRequest = onDismiss,
        visuals = RipDpiDialogVisuals(message = listOfNotNull(result.summary, result.detail).joinToString("\n\n")),
        dismissAction = RipDpiDialogAction(stringResource(R.string.action_dismiss), onDismiss),
    )
}
