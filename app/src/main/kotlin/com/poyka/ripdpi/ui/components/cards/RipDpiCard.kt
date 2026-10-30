package com.poyka.ripdpi.ui.components.cards

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val disabledAlpha = 0.38f

enum class RipDpiCardVariant {
    Outlined,
    Tonal,
    Elevated,
    Status,
    Selected,
}

/** Material 3 [Card] drawn with the RDS surface role of the variant; the outlined variant is the default. */
@Composable
fun RipDpiCard(
    modifier: Modifier = Modifier,
    variant: RipDpiCardVariant = RipDpiCardVariant.Outlined,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    paddingValues: PaddingValues = PaddingValues(RipDpiThemeTokens.layout.cardPadding),
    // Animate size changes inside the card. A caller's animateContentSize() clips to the
    // rectangular bounds and cuts the rounded shadow into a square band.
    animateContentSize: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val shape = RipDpiThemeTokens.shapes.xl
    val surfaceStyle =
        RipDpiThemeTokens.surfaces.resolve(
            RipDpiThemeTokens.surfaceRoles.cards.fromVariant(variant),
        )
    val colors =
        CardDefaults.cardColors(
            containerColor = surfaceStyle.container,
            contentColor = surfaceStyle.content,
            disabledContainerColor = surfaceStyle.container,
            disabledContentColor = surfaceStyle.content,
        )
    val elevation =
        CardDefaults.cardElevation(
            defaultElevation = surfaceStyle.shadowElevation,
            disabledElevation = surfaceStyle.shadowElevation,
        )
    val border =
        if (surfaceStyle.border == Color.Transparent) null else BorderStroke(RipDpiStroke.Thin, surfaceStyle.border)
    val cardModifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else disabledAlpha)
    val cardContent: @Composable ColumnScope.() -> Unit = {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (animateContentSize) {
                            Modifier.animateContentSize(animationSpec = RipDpiThemeTokens.motion.stateTween())
                        } else {
                            Modifier
                        },
                    ).padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
            content = content,
        )
    }
    if (onClick != null) {
        val performHaptic = rememberRipDpiHapticPerformer()
        Card(
            onClick = {
                performHaptic(RipDpiHapticFeedback.Action)
                onClick()
            },
            modifier = cardModifier,
            enabled = enabled,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            content = cardContent,
        )
    } else {
        Card(
            modifier = cardModifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            content = cardContent,
        )
    }
}

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
