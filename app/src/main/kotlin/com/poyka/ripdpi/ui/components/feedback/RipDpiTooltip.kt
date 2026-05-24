package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * Branded plain tooltip: foreground container, background content
 * text, monoSmall type for technical strings. Wraps Material3
 * TooltipBox; consumer supplies the anchor composable as [content].
 *
 * Matches `components-tooltip.html` (plain variant only; rich
 * variant deferred).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RipDpiTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val state = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(
                containerColor = colors.foreground,
                contentColor = colors.background,
            ) {
                Text(text = text, style = RipDpiThemeTokens.type.monoSmall)
            }
        },
        state = state,
        modifier = modifier,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "RipDpiTooltip (light)")
@Composable
private fun RipDpiTooltipPreviewLight() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            RipDpiTooltip(text = "Press to reconnect tunnel") {
                Text("Reconnect", style = RipDpiThemeTokens.type.bodyEmphasis)
            }
            RipDpiTooltip(text = "rtt 32 ms · 0.2% loss") {
                Text("32 ms", style = RipDpiThemeTokens.type.monoValue)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "RipDpiTooltip (dark)")
@Composable
private fun RipDpiTooltipPreviewDark() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiTooltip(text = "Open command palette · ⌘K") {
            Text("⌘K", style = RipDpiThemeTokens.type.monoSmall)
        }
    }
}
