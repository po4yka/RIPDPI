package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.PhaseState
import com.poyka.ripdpi.activities.PhaseStepUiModel
import com.poyka.ripdpi.ui.components.indicators.ripDpiMetricToneStyle
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList

private const val PhaseStepActiveAlpha = 1f
private const val PhaseStepCompletedAlpha = 0.72f
private const val PhaseStepPendingAlpha = 0.38f

/** The summary changes only with the active phase; every phase keeps its own readable state. */
@Composable
internal fun DiagnosticsPhaseStrip(steps: ImmutableList<PhaseStepUiModel>) {
    val activePhase = steps.firstOrNull { it.state == PhaseState.Active }
    val activeDescription = stringResource(R.string.onboarding_test_row_state_active)
    LazyRow(
        modifier =
            Modifier.semantics(mergeDescendants = false) {
                activePhase?.let {
                    contentDescription = it.label
                    stateDescription = activeDescription
                    liveRegion = LiveRegionMode.Polite
                }
            },
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        items(items = steps, key = { it.label }, contentType = { "phase_chip" }) { step ->
            DiagnosticsPhaseChip(step)
        }
    }
}

@Composable
private fun DiagnosticsPhaseChip(step: PhaseStepUiModel) {
    val motion = RipDpiThemeTokens.motion
    val animateFeedback = motion.animationsEnabled && !motion.reducedMotion && !LocalInspectionMode.current
    val style = ripDpiMetricToneStyle(metricTone(step.tone))
    val stateLabel =
        stringResource(
            when (step.state) {
                PhaseState.Completed -> R.string.onboarding_test_row_state_done
                PhaseState.Active -> R.string.onboarding_test_row_state_active
                PhaseState.Pending -> R.string.onboarding_test_row_state_pending
            },
        )
    val targetAlpha =
        when (step.state) {
            PhaseState.Completed -> PhaseStepCompletedAlpha
            PhaseState.Active -> PhaseStepActiveAlpha
            PhaseState.Pending -> PhaseStepPendingAlpha
        }
    val containerColor by
        if (animateFeedback) {
            animateColorAsState(style.container, motion.quickTween(), label = "phaseContainer")
        } else {
            rememberUpdatedState(style.container)
        }
    val contentColor by
        if (animateFeedback) {
            animateColorAsState(style.content, motion.quickTween(), label = "phaseContent")
        } else {
            rememberUpdatedState(style.content)
        }
    val textAlpha by
        if (animateFeedback) {
            animateFloatAsState(targetAlpha, motion.quickTween(), label = "phaseTextAlpha")
        } else {
            rememberUpdatedState(targetAlpha)
        }
    val targetMarkAlpha = if (step.state == PhaseState.Completed) 1f else 0f
    val markAlpha by
        if (animateFeedback) {
            animateFloatAsState(targetMarkAlpha, motion.quickTween(), label = "phaseCompletionAlpha")
        } else {
            rememberUpdatedState(targetMarkAlpha)
        }
    Surface(
        modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = stateLabel },
        color = containerColor,
        contentColor = contentColor,
        shape = RipDpiThemeTokens.shapes.full,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = RipDpiThemeTokens.spacing.sm, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Reserve the original prefix width so completion does not move the next phase or focus bounds.
            Text(
                text = "✓ ",
                modifier = Modifier.clearAndSetSemantics {}.graphicsLayer { alpha = markAlpha },
                style = RipDpiThemeTokens.type.monoSmall,
                color = contentColor.copy(alpha = textAlpha),
            )
            Text(
                text = step.label,
                style = RipDpiThemeTokens.type.monoSmall,
                color = contentColor.copy(alpha = textAlpha),
            )
        }
    }
}

/** Keep probe text available on focus without making each probe a live announcement. */
@Composable
internal fun DiagnosticsCurrentProbeLabel(label: String) {
    val motion = RipDpiThemeTokens.motion
    Box(modifier = Modifier.clearAndSetSemantics { text = AnnotatedString(label) }) {
        if (!motion.animationsEnabled || motion.reducedMotion || LocalInspectionMode.current) {
            ProbeLabelText(label)
        } else {
            AnimatedContent(
                targetState = label,
                transitionSpec = { motion.quickFadeContentTransform().using(null) },
                label = "scanProgressLabel",
            ) { visibleLabel ->
                ProbeLabelText(visibleLabel)
            }
        }
    }
}

@Composable
private fun ProbeLabelText(label: String) {
    Text(
        text = label,
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = RipDpiThemeTokens.colors.foreground,
    )
}
