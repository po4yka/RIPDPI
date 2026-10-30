@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.theme

import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.animate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Cached recipes read the current RDS tokens only when their attached resolver runs. */
object RipDpiComponentStyles {
    private val surfaces = RipDpiSurfaceRole.entries.associateWith { surfaceStyle(it) }
    private val outlinedSurfaces = RipDpiSurfaceRole.entries.associateWith { surfaceStyle(it, outlined = true) }

    /** Border width, shape, shadow, and disabled content alpha remain component-owned. */
    fun surface(role: RipDpiSurfaceRole): CommonStyle = surfaces.getValue(role)

    fun outlinedSurface(role: RipDpiSurfaceRole): CommonStyle = outlinedSurfaces.getValue(role)

    val chip =
        CommonStyle {
            val tokens = LocalRipDpiStateTokens.currentValue
            val components = LocalRipDpiComponents.currentValue
            val motion = LocalRipDpiMotion.currentValue
            val resolved = tokens.chip.resolve(state.isSelected, state.isEnabled, state.isPressed)
            animate(motion.stateTween<Float>()) {
                rdsBackground(resolved.container)
                RipDpiStyleProperties.borderColor.provide(resolved.border)
            }
            RipDpiStyleProperties.borderWidth.provide(RipDpiStroke.Thin)
            rdsCornerRadius(resolved.cornerRadius)
            rdsContentScale(resolved.scale)
            val compact = state[RipDpiStyleStateKeys.Compact]
            val horizontal =
                components.inputs.chipHorizontalPadding -
                    if (compact) components.inputs.chipFocusedHorizontalPaddingOffset else 0.dp
            val vertical =
                components.inputs.chipVerticalPadding -
                    if (compact) components.inputs.chipFocusedVerticalPaddingOffset else 0.dp
            rdsContentPadding(horizontal, vertical, horizontal, vertical)
            // The existing chip alpha applies only after its shell and remains explicit.
        }

    val textField =
        CommonStyle {
            val resolved =
                LocalRipDpiStateTokens.currentValue.textField.resolve(
                    enabled = state.isEnabled,
                    hasError = state[RipDpiStyleStateKeys.HasError],
                    isFocused = state.isFocused,
                    isEmpty = state[RipDpiStyleStateKeys.IsEmpty],
                )
            val inputs = LocalRipDpiComponents.currentValue.inputs
            rdsBackground(resolved.container)
            rdsBorder(resolved.border, resolved.borderWidth)
            rdsContentAlpha(resolved.alpha)
            val basePadding =
                if (resolved.borderWidth > RipDpiStroke.Thin) {
                    inputs.fieldFocusedHorizontalPadding
                } else {
                    inputs.fieldHorizontalPadding
                }
            val padding = basePadding - if (state[RipDpiStyleStateKeys.Compact]) 4.dp else 0.dp
            rdsContentPadding(start = padding, end = padding)
        }

    val dropdown =
        CommonStyle {
            val focused =
                state.isEnabled && !state[RipDpiStyleStateKeys.ReadOnly] &&
                    (state[RipDpiStyleStateKeys.Expanded] || state.isFocused)
            val resolved =
                LocalRipDpiStateTokens.currentValue.textField.resolve(
                    enabled = state.isEnabled,
                    hasError = state[RipDpiStyleStateKeys.HasError],
                    isFocused = focused,
                    isEmpty = state[RipDpiStyleStateKeys.IsEmpty],
                )
            val inputs = LocalRipDpiComponents.currentValue.inputs
            val motion = LocalRipDpiMotion.currentValue
            rdsBackground(resolved.container)
            animate(motion.stateTween<Float>()) { RipDpiStyleProperties.borderColor.provide(resolved.border) }
            animate(motion.quickTween<Float>()) { RipDpiStyleProperties.borderWidth.provide(resolved.borderWidth) }
            rdsContentAlpha(resolved.alpha)
            val basePadding =
                if (resolved.borderWidth > RipDpiStroke.Thin) {
                    inputs.fieldFocusedHorizontalPadding
                } else {
                    inputs.fieldHorizontalPadding
                }
            val padding = basePadding - if (state[RipDpiStyleStateKeys.Compact]) 4.dp else 0.dp
            rdsContentPadding(start = padding, end = padding)
        }

    val screenBackground =
        CommonStyle {
            rdsBackground(LocalRipDpiExtendedColors.currentValue.background)
        }

    val segmentedGroup =
        CommonStyle {
            val colors = LocalRipDpiExtendedColors.currentValue
            rdsBackground(colors.muted)
            rdsBorder(colors.border, RipDpiStroke.Thin)
        }

    val segmentedItem =
        CommonStyle {
            val colors = LocalRipDpiExtendedColors.currentValue
            rdsBackground(if (state.isSelected) colors.foreground else colors.muted)
        }
}

private fun surfaceStyle(
    role: RipDpiSurfaceRole,
    outlined: Boolean = false,
) = CommonStyle {
    val resolved = LocalRipDpiSurfaceTokens.currentValue.resolve(role)
    rdsBackground(resolved.container)
    RipDpiStyleProperties.borderColor.provide(resolved.border)
    if (outlined) {
        RipDpiStyleProperties.borderWidth.provide(if (resolved.border == Color.Transparent) 0.dp else RipDpiStroke.Thin)
    }
}
