package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.indicators.RipDpiProgressBar
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val ProgressPercentFactor = 100

/** Smooths measured forward updates while accessibility always exposes the current measurement. */
@Composable
internal fun DiagnosticsMeasuredProgress(
    progress: Float?,
    scanIdentity: Long,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val measured = progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    val description =
        contentDescription ?: measured?.let {
            stringResource(R.string.cd_progress_percent, (it * ProgressPercentFactor).toInt())
        } ?: stringResource(R.string.cd_loading)
    val motion = RipDpiThemeTokens.motion
    val allowsInterpolation = motion.animationsEnabled && !motion.reducedMotion && !LocalInspectionMode.current

    // Keep this node stable when the run, motion policy, or known/unknown state changes. Only the
    // visual child is replaced. Clear the shared bar's live region and interpolated range semantics.
    Box(
        modifier.clearAndSetSemantics {
            this.contentDescription = description
            progressBarRangeInfo = measured?.let { ProgressBarRangeInfo(it, 0f..1f) }
                ?: ProgressBarRangeInfo.Indeterminate
        },
    ) {
        key(scanIdentity, measured == null, allowsInterpolation) {
            if (measured == null || !allowsInterpolation) {
                RipDpiProgressBar(progress = measured)
            } else {
                val animation = remember { MeasuredProgressAnimation(measured) }
                // Select a fresh value for corrections so this composition cannot show a stale
                // fill, even if its effect is cancelled before it starts.
                val fill =
                    if (measured < animation.lastMeasurement) {
                        remember(measured) { Animatable(measured) }
                    } else {
                        animation.fill
                    }
                SideEffect {
                    animation.lastMeasurement = measured
                    animation.fill = fill
                }
                LaunchedEffect(fill, measured) {
                    if (fill.value != measured) {
                        fill.animateTo(measured, motion.stateTween())
                    }
                }
                RipDpiProgressBar(progress = fill.value)
            }
        }
    }
}

private class MeasuredProgressAnimation(
    initialMeasurement: Float,
) {
    var fill = Animatable(initialMeasurement)
    var lastMeasurement = initialMeasurement
}
