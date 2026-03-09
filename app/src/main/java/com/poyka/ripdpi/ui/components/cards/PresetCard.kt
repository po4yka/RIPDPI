package com.poyka.ripdpi.ui.components.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun PresetCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val scheme = MaterialTheme.colorScheme
    val shape = RipDpiThemeTokens.shapes.xl
    val borderColor = if (selected) colors.foreground else colors.cardBorder
    val background =
        if (selected) {
            lerp(scheme.background, colors.foreground, if (scheme.background.luminance() < 0.5f) 0.04f else 0.05f)
        } else {
            MaterialTheme.colorScheme.surface
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 65.dp)
                .background(background, shape)
                .border(1.dp, borderColor, shape)
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClick,
                ).padding(horizontal = components.fieldHorizontalPadding, vertical = 13.dp)
                .alpha(if (enabled) 1f else 0.38f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = colors.foreground,
            )
            badgeText?.let {
                Box(
                    modifier =
                        Modifier
                            .background(
                                colors.foreground.copy(alpha = if (selected) 0.12f else 0.08f),
                                RipDpiThemeTokens.shapes.xxl,
                            ).padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = it,
                        style = RipDpiThemeTokens.type.smallLabel,
                        color = colors.foreground,
                    )
                }
            }
        }
        Text(
            text = description,
            style = RipDpiThemeTokens.type.caption,
            color = colors.mutedForeground,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PresetCardPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetCard(
                title = "Auto",
                description = "Detect and apply best DPI strategy",
                badgeText = "Active",
                selected = true,
                onClick = {},
            )
            PresetCard(
                title = "desync (fake)",
                description = "Send fake packets before real data",
                onClick = {},
            )
        }
    }
}
