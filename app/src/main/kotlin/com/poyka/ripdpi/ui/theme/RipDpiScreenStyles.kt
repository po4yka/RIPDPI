@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.theme

import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared feature shells. Layout, behavior, text, and clipping remain caller-owned. */
internal object RipDpiScreenStyles {
    val inputBadge = CommonStyle { rdsBackground(LocalRipDpiExtendedColors.currentValue.inputBackground) }
    val mutedBackground = CommonStyle { rdsBackground(LocalRipDpiExtendedColors.currentValue.muted) }
    val mutedPanel =
        CommonStyle {
            val colors = LocalRipDpiExtendedColors.currentValue
            rdsBackground(colors.muted)
            rdsBorder(colors.outline, RipDpiStroke.Thin)
        }
    val cardPanel =
        CommonStyle {
            val colors = LocalRipDpiExtendedColors.currentValue
            rdsBackground(colors.card)
            rdsBorder(colors.border, RipDpiStroke.Thin)
        }
    val backgroundPanel =
        CommonStyle {
            val colors = LocalRipDpiExtendedColors.currentValue
            rdsBackground(colors.background)
            rdsBorder(colors.border, RipDpiStroke.Thin)
        }
}

internal val RipDpiComponentStyles.screens: RipDpiScreenStyles
    get() = RipDpiScreenStyles

/** Bind a fresh resolver at each component call site, even when a recipe is shared. */
@Composable
internal fun Modifier.ripDpiScreenSurface(
    style: CommonStyle,
    shape: Shape = RectangleShape,
    clip: Boolean = false,
): Modifier {
    val state = rememberUpdatedStyleState(null)
    val resolver = rememberRipDpiStyleResolver(style, CommonStyle, state)
    return ripDpiStyleSurface(resolver, shape = shape, clip = clip)
}

@Composable
internal fun Modifier.ripDpiScreenBackground(): Modifier =
    ripDpiScreenSurface(RipDpiThemeTokens.styles.screenBackground)

/** Runtime feature colors are inputs; token-only shells use the cached recipes above. */
@Composable
internal fun Modifier.ripDpiFeatureSurface(
    container: Color = Color.Unspecified,
    shape: Shape = RectangleShape,
    border: Color = Color.Unspecified,
    borderWidth: Dp = 0.dp,
    clip: Boolean = false,
): Modifier {
    val style =
        remember(container, border, borderWidth) {
            CommonStyle {
                if (container.isSpecified) rdsBackground(container)
                if (border.isSpecified) rdsBorder(border, borderWidth)
            }
        }
    return ripDpiScreenSurface(style, shape = shape, clip = clip)
}
