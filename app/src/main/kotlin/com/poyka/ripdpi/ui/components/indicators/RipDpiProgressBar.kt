package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val ProgressPercentFactor = 100
private val ProgressBarHeight = 4.dp

/**
 * Branded determinate linear progress. Wraps Material3
 * [LinearProgressIndicator] using `RipDpiThemeTokens.colors.foreground`
 * for the fill and `colors.muted` for the track. Pass `progress = null`
 * for an indeterminate animation.
 *
 * Matches `components-progress-bar.html`.
 */
@Composable
fun RipDpiProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    val foreground = RipDpiThemeTokens.colors.foreground
    val track = RipDpiThemeTokens.colors.muted
    val progressDescription =
        if (progress != null) {
            // Clamped like the fill below: an out-of-range value must not make a screen reader
            // announce a percentage the bar never draws.
            stringResource(
                R.string.cd_progress_percent,
                (progress.coerceIn(0f, 1f) * ProgressPercentFactor).toInt(),
            )
        } else {
            stringResource(R.string.cd_loading)
        }
    val baseModifier =
        modifier
            .fillMaxWidth()
            .height(ProgressBarHeight)
            .semantics { contentDescription = progressDescription }
    if (progress == null) {
        LinearProgressIndicator(
            modifier = baseModifier,
            color = foreground,
            trackColor = track,
        )
    } else {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = baseModifier,
            color = foreground,
            trackColor = track,
        )
    }
}

@Preview(showBackground = true, name = "RipDpiProgressBar (light)")
@Composable
private fun RipDpiProgressBarLightPreview() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            RipDpiProgressBar(progress = 0.0f)
            RipDpiProgressBar(progress = 0.35f)
            RipDpiProgressBar(progress = 0.78f)
            RipDpiProgressBar(progress = 1.0f)
            RipDpiProgressBar(progress = null)
        }
    }
}

@Preview(showBackground = true, name = "RipDpiProgressBar (dark)")
@Composable
private fun RipDpiProgressBarDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            RipDpiProgressBar(progress = 0.5f)
            RipDpiProgressBar(progress = null)
        }
    }
}
