@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.theme

import androidx.compose.foundation.style.CommonStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Cached recipes read the current RDS tokens only when their attached resolver runs. */
object RipDpiComponentStyles {
    private val surfaces = RipDpiSurfaceRole.entries.associateWith { surfaceStyle(it) }
    private val outlinedSurfaces = RipDpiSurfaceRole.entries.associateWith { surfaceStyle(it, outlined = true) }

    /** Border width, shape, shadow, and disabled content alpha remain component-owned. */
    fun surface(role: RipDpiSurfaceRole): CommonStyle = surfaces.getValue(role)

    fun outlinedSurface(role: RipDpiSurfaceRole): CommonStyle = outlinedSurfaces.getValue(role)

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
