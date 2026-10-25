@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.cards

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.rememberRipDpiStyleResolver
import com.poyka.ripdpi.ui.theme.ripDpiStyleSurface

private const val disabledAlpha = 0.38f

enum class RipDpiCardVariant {
    Outlined,
    Tonal,
    Elevated,
    Status,
    Selected,
}

@Composable
@NonRestartableComposable
fun RipDpiCard(
    modifier: Modifier = Modifier,
    variant: RipDpiCardVariant = RipDpiCardVariant.Outlined,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    paddingValues: PaddingValues = PaddingValues(RipDpiThemeTokens.layout.cardPadding),
    animateContentSize: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    RipDpiCard(
        modifier = modifier,
        variant = variant,
        enabled = enabled,
        onClick = onClick,
        paddingValues = paddingValues,
        animateContentSize = animateContentSize,
        content = content,
        visualStyle = CommonStyle,
    )
}

@Composable
fun RipDpiCard(
    modifier: Modifier = Modifier,
    variant: RipDpiCardVariant = RipDpiCardVariant.Outlined,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    paddingValues: PaddingValues = PaddingValues(RipDpiThemeTokens.layout.cardPadding),
    visualStyle: CommonStyle = CommonStyle,
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
    val interactionSource = remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(interactionSource) { it.isEnabled = enabled }
    val styleResolver =
        rememberRipDpiStyleResolver(
            RipDpiThemeTokens.styles.outlinedSurface(RipDpiThemeTokens.surfaceRoles.cards.fromVariant(variant)),
            visualStyle,
            styleState,
        )
    val cardModifier =
        modifier
            .fillMaxWidth()
            .shadow(surfaceStyle.shadowElevation, shape, clip = false)
            .ripDpiStyleSurface(styleResolver, shape, clip = true)
            .alpha(if (enabled) 1f else disabledAlpha)
            .then(
                if (animateContentSize) {
                    Modifier.animateContentSize(animationSpec = RipDpiThemeTokens.motion.stateTween())
                } else {
                    Modifier
                },
            ).then(
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
