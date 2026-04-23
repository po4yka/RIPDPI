package com.poyka.ripdpi.ui.components.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val disabledAlpha = 0.38f

enum class RipDpiCardVariant {
    Outlined,
    Tonal,
    Elevated,
    Status,
}

@Composable
fun RipDpiCard(
    modifier: Modifier = Modifier,
    variant: RipDpiCardVariant = RipDpiCardVariant.Outlined,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    paddingValues: PaddingValues = PaddingValues(RipDpiThemeTokens.layout.cardPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val shape = RipDpiThemeTokens.shapes.xl
    val surfaceStyle =
        RipDpiThemeTokens.surfaces.resolve(
            RipDpiThemeTokens.surfaceRoles.cards.fromVariant(variant),
        )
    val interactionSource = remember { MutableInteractionSource() }
    val cardModifier =
        modifier
            .fillMaxWidth()
            .shadow(surfaceStyle.shadowElevation, shape, clip = false)
            .clip(shape)
            .background(surfaceStyle.container, shape)
            .border(if (surfaceStyle.border == Color.Transparent) 0.dp else 1.dp, surfaceStyle.border, shape)
            .alpha(if (enabled) 1f else disabledAlpha)
            .then(
                if (onClick != null) {
                    Modifier.ripDpiClickable(
                        enabled = enabled,
                        role = Role.Button,
                        interactionSource = interactionSource,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ).padding(paddingValues)

    Column(
        modifier = cardModifier,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
        content = content,
    )
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun RipDpiCardPreview() {
    RipDpiComponentPreview {
        RipDpiCard {
            Text(
                text = "Outlined card",
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
            )
            Text(
                text = "Designed for monochrome list blocks and grouped controls.",
                style = RipDpiThemeTokens.type.secondaryBody,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
        RipDpiCard(variant = RipDpiCardVariant.Tonal) {
            Text(
                text = "Tonal card",
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
            )
            Text(
                text = "Use for inline groups and selected settings content.",
                style = RipDpiThemeTokens.type.secondaryBody,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
        RipDpiCard(variant = RipDpiCardVariant.Elevated) {
            Text(
                text = "Elevated card",
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
            )
            Text(
                text = "Use for dialogs, sheets, or higher-priority content.",
                style = RipDpiThemeTokens.type.secondaryBody,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
    }
}
