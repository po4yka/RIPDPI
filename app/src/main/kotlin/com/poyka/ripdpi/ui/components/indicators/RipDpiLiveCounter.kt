package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.text.NumberFormat
import java.util.Locale

private const val PreviewLatencyMillis = 1234
private const val PreviewSuccessPercent = 87
private const val PreviewByteCount = 42_500_000

/**
 * A monospace counter whose displayed value animates between integer
 * targets via [animateIntAsState]. The text style defaults to
 * `RipDpiThemeTokens.type.monoValue` to keep the digits tabular and
 * non-jittery during interpolation.
 *
 * Matches `components-live-counter.html` — animated metric value that
 * always reads as machine-data (mono font).
 */
@Composable
fun RipDpiLiveCounter(
    value: Int,
    modifier: Modifier = Modifier,
    suffix: String = "",
    style: TextStyle = RipDpiThemeTokens.type.monoValue,
) {
    val motion = RipDpiThemeTokens.motion
    val animated by animateIntAsState(
        targetValue = value,
        animationSpec = motion.stateTween(),
        label = "liveCounter",
    )
    val display = "${NumberFormat.getInstance(Locale.getDefault()).format(animated)}$suffix"
    Text(
        text = display,
        modifier = modifier.semantics { contentDescription = display },
        style = style.copy(color = RipDpiThemeTokens.colors.foreground),
    )
}

@Preview(showBackground = true, name = "RipDpiLiveCounter (light)")
@Composable
private fun RipDpiLiveCounterLightPreview() {
    RipDpiComponentPreview {
        var n by remember { mutableIntStateOf(PreviewLatencyMillis) }
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            RipDpiLiveCounter(value = n, suffix = " ms")
            RipDpiLiveCounter(value = PreviewSuccessPercent, suffix = " %")
            RipDpiLiveCounter(value = PreviewByteCount, suffix = " B")
        }
    }
}

@Preview(showBackground = true, name = "RipDpiLiveCounter (dark)")
@Composable
private fun RipDpiLiveCounterDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiLiveCounter(value = PreviewLatencyMillis, suffix = " ms")
    }
}
