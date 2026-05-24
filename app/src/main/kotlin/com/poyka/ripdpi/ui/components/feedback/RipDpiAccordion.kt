package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * Single-row expandable accordion. Title row + trailing chevron that
 * rotates 180° on expansion. Content panel reveals with
 * AnimatedVisibility and `animateContentSize`. Visual rule: 1dp
 * border, card container, divider between header and body when open.
 *
 * Matches `components-accordion.html`.
 */
@Composable
fun RipDpiAccordion(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val shape = RoundedCornerShape(RipDpiThemeTokens.spacing.md)
    val chevronAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = motion.stateTween(),
        label = "accordionChevron",
    )
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.card, shape)
                .border(width = 1.dp, color = colors.cardBorder, shape = shape)
                .animateContentSize(animationSpec = motion.stateTween()),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiClickable(enabled = true) { onExpandedChange(!expanded) }
                    .padding(
                        horizontal = RipDpiThemeTokens.spacing.lg,
                        vertical = RipDpiThemeTokens.spacing.md,
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = title, style = RipDpiThemeTokens.type.bodyEmphasis.copy(color = colors.foreground))
            Icon(
                imageVector = RipDpiIcons.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = colors.mutedForeground,
                modifier = Modifier.size(20.dp).rotate(chevronAngle),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = RipDpiThemeTokens.spacing.lg,
                            vertical = RipDpiThemeTokens.spacing.md,
                        ),
            ) {
                body()
            }
        }
    }
}

@Preview(showBackground = true, name = "RipDpiAccordion (light)")
@Composable
private fun RipDpiAccordionPreviewLight() {
    RipDpiComponentPreview {
        var open1 by remember { mutableStateOf(true) }
        var open2 by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            RipDpiAccordion(title = "Advanced settings", expanded = open1, onExpandedChange = { open1 = it }) {
                Text(
                    "Inside content — DNS, kill switch, split tunnel.",
                    style = RipDpiThemeTokens.type.secondaryBody.copy(color = RipDpiThemeTokens.colors.mutedForeground),
                )
            }
            RipDpiAccordion(title = "Diagnostics history", expanded = open2, onExpandedChange = { open2 = it }) {
                Text("Hidden by default", style = RipDpiThemeTokens.type.body)
            }
        }
    }
}

@Preview(showBackground = true, name = "RipDpiAccordion (dark)")
@Composable
private fun RipDpiAccordionPreviewDark() {
    RipDpiComponentPreview(themePreference = "dark") {
        var open by remember { mutableStateOf(true) }
        RipDpiAccordion(title = "Strategy details", expanded = open, onExpandedChange = { open = it }) {
            Text("desync-tlsrec, fake-ttl=4, OOB=0x00", style = RipDpiThemeTokens.type.monoSmall)
        }
    }
}
