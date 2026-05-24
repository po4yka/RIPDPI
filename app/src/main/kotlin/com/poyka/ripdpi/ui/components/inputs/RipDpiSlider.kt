package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * Branded continuous slider wrapping Material3 [Slider]. Active track
 * and thumb use foreground; inactive track uses muted. Optional label
 * row shows the rounded current value in mono type.
 *
 * Matches `components-slider.html`.
 */
@Composable
fun RipDpiSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    label: String? = null,
    valueFormatter: (Float) -> String = { "%.2f".format(it) },
) {
    val colors = RipDpiThemeTokens.colors
    Column(modifier = modifier) {
        if (label != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = RipDpiThemeTokens.type.secondaryBody.copy(color = colors.mutedForeground))
                Text(valueFormatter(value), style = RipDpiThemeTokens.type.monoSmall.copy(color = colors.foreground))
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = label ?: "Slider" },
            colors =
                SliderDefaults.colors(
                    thumbColor = colors.foreground,
                    activeTrackColor = colors.foreground,
                    inactiveTrackColor = colors.muted,
                    activeTickColor = colors.background,
                    inactiveTickColor = colors.mutedForeground,
                ),
        )
    }
}

@Preview(showBackground = true, name = "RipDpiSlider (light)")
@Composable
private fun RipDpiSliderPreviewLight() {
    RipDpiComponentPreview {
        var v by remember { mutableFloatStateOf(0.6f) }
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            RipDpiSlider(value = v, onValueChange = { v = it }, label = "Throughput cap")
            RipDpiSlider(value = 0.3f, onValueChange = {}, label = "MTU", steps = 4)
        }
    }
}

@Preview(showBackground = true, name = "RipDpiSlider (dark)")
@Composable
private fun RipDpiSliderPreviewDark() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiSlider(value = 0.5f, onValueChange = {}, label = "Detection cooldown")
    }
}
