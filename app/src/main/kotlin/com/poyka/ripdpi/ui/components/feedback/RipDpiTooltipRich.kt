package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * Branded rich tooltip: a title row + body slot inside a card-tinted
 * container. For longer-form explanations than [RipDpiTooltip]. Wraps
 * Material3 TooltipBox + RichTooltip.
 *
 * Matches `components-tooltip.html` (rich variant).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RipDpiTooltipRich(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val state = rememberTooltipState(isPersistent = true)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            RichTooltip(
                title = {
                    Text(
                        text = title,
                        style = RipDpiThemeTokens.type.bodyEmphasis,
                    )
                },
                action =
                    actionLabel?.let {
                        {
                            Text(
                                text = it,
                                modifier =
                                    Modifier
                                        .padding(RipDpiThemeTokens.spacing.sm)
                                        .ripDpiClickable(onClick = onAction),
                                style = RipDpiThemeTokens.type.button.copy(color = colors.info),
                            )
                        }
                    },
                colors = TooltipDefaults.richTooltipColors(containerColor = colors.card),
            ) {
                Column(
                    modifier =
                        Modifier
                            .widthIn(max = RipDpiThemeTokens.components.tooltip.richMaxWidth)
                            .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
                ) {
                    Text(
                        text = body,
                        style =
                            RipDpiThemeTokens.type.secondaryBody.copy(color = colors.foreground),
                    )
                }
            }
        },
        state = state,
        modifier = modifier,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "RipDpiTooltipRich (light)")
@Composable
private fun RipDpiTooltipRichLightPreview() {
    RipDpiComponentPreview {
        RipDpiTooltipRich(
            title = "Stale data",
            body = "Last probe was 18 minutes ago. Refresh before acting; the network may have shifted.",
            actionLabel = "Probe now",
        ) {
            Text("18 m ago", style = RipDpiThemeTokens.type.monoSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "RipDpiTooltipRich (dark)")
@Composable
private fun RipDpiTooltipRichDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiTooltipRich(
            title = "Strategy A/B",
            body = "Compare two desync strategies side-by-side on the next 10 connections.",
        ) {
            Text("?", style = RipDpiThemeTokens.type.bodyEmphasis)
        }
    }
}
