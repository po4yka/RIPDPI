package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

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
    val baseModifier =
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .semantics {
                contentDescription =
                    if (progress != null) "Progress ${(progress * 100).toInt()}%" else "Loading"
            }
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
private fun RipDpiProgressBarPreviewLight() {
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
private fun RipDpiProgressBarPreviewDark() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            RipDpiProgressBar(progress = 0.5f)
            RipDpiProgressBar(progress = null)
        }
    }
}
