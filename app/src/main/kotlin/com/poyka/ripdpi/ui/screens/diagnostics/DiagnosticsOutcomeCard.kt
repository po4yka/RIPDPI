package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsOutcomeSource
import com.poyka.ripdpi.activities.DiagnosticsOutcomeUiModel
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun DiagnosticsOutcomeCard(
    outcome: DiagnosticsOutcomeUiModel,
    runNetworkCheck: Boolean,
    homeDiagnostics: HomeDiagnosticsUiState,
    onRunScan: () -> Unit,
    onOpenScan: () -> Unit,
    onStartVerifiedVpn: () -> Unit,
) {
    val active = outcome.source == DiagnosticsOutcomeSource.Active
    RipDpiCard(
        variant = RipDpiCardVariant.Elevated,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsOverviewHero),
    ) {
        DiagnosticsOutcomeTransition(outcome)
        RipDpiButton(
            text =
                stringResource(
                    if (runNetworkCheck) {
                        R.string.diagnostics_simple_funnel_check_action
                    } else {
                        R.string.diagnostics_overview_run_scan_action
                    },
                ),
            onClick = onRunScan,
            enabled = !active,
            modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.DiagnosticsOverviewRunScanAction),
        )
        if (active) {
            RipDpiButton(
                text = stringResource(R.string.diagnostics_scan_section),
                onClick = onOpenScan,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DiagnosticsOutcomeMetadata(outcome)
        if (outcome.source == DiagnosticsOutcomeSource.CompositeAudit &&
            homeDiagnostics.verifiedVpnAction.label.isNotBlank()
        ) {
            DiagnosticsGuidedActions(homeDiagnostics, {}, onStartVerifiedVpn, showSummary = false)
        }
    }
}

@Composable
private fun DiagnosticsOutcomeTransition(outcome: DiagnosticsOutcomeUiModel) {
    val motion = RipDpiThemeTokens.motion
    Box(Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.DiagnosticsOutcomeTransition)) {
        if (!motion.animationsEnabled || motion.reducedMotion) {
            DiagnosticsOutcomeSummary(outcome)
        } else {
            // Text updates keep the same holder and do not restart completion motion.
            val content = key(outcome.source, outcome.evidenceId) { rememberUpdatedState(outcome) }
            AnimatedContent(
                targetState = content,
                transitionSpec = {
                    val completion =
                        initialState.value.source == DiagnosticsOutcomeSource.Active &&
                            (
                                targetState.value.source == DiagnosticsOutcomeSource.Session ||
                                    targetState.value.source == DiagnosticsOutcomeSource.CompositeAudit
                            )
                    if (completion) {
                        (
                            fadeIn(animationSpec = motion.stateTween()) togetherWith
                                fadeOut(animationSpec = motion.quickTween())
                        ).using(null)
                    } else {
                        (EnterTransition.None togetherWith ExitTransition.None).using(null)
                    }
                },
                label = "diagnosticsOutcome",
            ) { displayed ->
                DiagnosticsOutcomeSummary(
                    outcome = displayed.value,
                    modifier = if (displayed === content) Modifier else Modifier.semantics { hideFromAccessibility() },
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsOutcomeSummary(
    outcome: DiagnosticsOutcomeUiModel,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val active = outcome.source == DiagnosticsOutcomeSource.Active
    Column(modifier, verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
        Text(stringResource(outcome.source.label()), style = type.sectionTitle, color = colors.mutedForeground)
        Text(
            outcome.headline?.takeIf(String::isNotBlank)
                ?: stringResource(
                    if (active) {
                        R.string.diagnostics_simple_funnel_verdict_running
                    } else {
                        R.string.diagnostics_headline_no_data
                    },
                ),
            style = type.cardTitle,
            color = colors.foreground,
        )
        Text(
            outcome.summary?.takeIf(String::isNotBlank) ?: stringResource(R.string.diagnostics_simple_funnel_body),
            style = type.body,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun DiagnosticsOutcomeMetadata(outcome: DiagnosticsOutcomeUiModel) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    Column(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsOutcomeMetadata),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        Text(
            outcome.measuredAt?.let { stringResource(R.string.diagnostics_outcome_time_format, it) }
                ?: stringResource(R.string.diagnostics_outcome_time_unknown),
            style = type.caption,
            color = colors.mutedForeground,
        )
        Text(
            outcome.confidence ?: stringResource(R.string.diagnostics_outcome_confidence_unknown),
            style = type.caption,
            color = colors.mutedForeground,
        )
        Text(
            outcome.coverage ?: if (outcome.totalStages > 0) {
                stringResource(
                    R.string.diagnostics_outcome_coverage_format,
                    outcome.completedStages,
                    outcome.totalStages,
                    outcome.failedStages,
                )
            } else {
                stringResource(R.string.diagnostics_outcome_coverage_unknown)
            },
            style = type.caption,
            color = colors.mutedForeground,
        )
        if (outcome.source == DiagnosticsOutcomeSource.Session ||
            outcome.source == DiagnosticsOutcomeSource.CompositeAudit
        ) {
            if (outcome.networkMatches != true) {
                Text(
                    stringResource(
                        if (outcome.stale) {
                            R.string.diagnostics_outcome_stale
                        } else {
                            R.string.diagnostics_outcome_historical
                        },
                    ),
                    style = type.caption,
                    color = colors.mutedForeground,
                )
            }
        }
    }
}

private fun DiagnosticsOutcomeSource.label(): Int =
    when (this) {
        DiagnosticsOutcomeSource.Active -> R.string.diagnostics_outcome_source_active
        DiagnosticsOutcomeSource.CompositeAudit -> R.string.diagnostics_outcome_source_audit
        DiagnosticsOutcomeSource.Session -> R.string.diagnostics_outcome_source_session
        DiagnosticsOutcomeSource.Empty -> R.string.diagnostics_outcome_source_empty
    }
