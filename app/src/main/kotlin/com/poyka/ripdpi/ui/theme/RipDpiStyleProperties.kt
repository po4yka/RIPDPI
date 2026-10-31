@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.theme

import androidx.compose.foundation.style.Fill
import androidx.compose.foundation.style.StylePropertyProviderScope
import androidx.compose.foundation.style.stylePropertyOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Properties owned by RDS. None of these properties inherit into child components. */
object RipDpiStyleProperties {
    val background = stylePropertyOf("rds.background") { Fill.None }
    val backgroundAlpha = stylePropertyOf("rds.backgroundAlpha") { 1f }
    val borderColor = stylePropertyOf("rds.borderColor") { Color.Unspecified }
    val borderWidth = stylePropertyOf("rds.borderWidth") { 0.dp }
    val cornerRadius = stylePropertyOf("rds.cornerRadius") { Dp.Unspecified }
    val containerAlpha = stylePropertyOf("rds.containerAlpha") { 1f }
    val minHeight = stylePropertyOf("rds.minHeight") { 0.dp }
}

fun StylePropertyProviderScope.rdsBackground(fill: Fill) {
    RipDpiStyleProperties.background.provide(fill)
}

fun StylePropertyProviderScope.rdsBackground(color: Color) {
    rdsBackground(Fill(color))
}

/** Replace the alpha of a solid background fill; brushes, children, and shadows are unchanged. */
fun StylePropertyProviderScope.rdsBackgroundAlpha(alpha: Float) {
    RipDpiStyleProperties.backgroundAlpha.provide(alpha)
}

fun StylePropertyProviderScope.rdsBorder(
    color: Color,
    width: Dp,
) {
    RipDpiStyleProperties.borderColor.provide(color)
    RipDpiStyleProperties.borderWidth.provide(width)
}

/** Supply a numeric radius so Styles can interpolate it before the shape is built. */
fun StylePropertyProviderScope.rdsCornerRadius(radius: Dp) {
    RipDpiStyleProperties.cornerRadius.provide(radius)
}

fun StylePropertyProviderScope.rdsMinHeight(height: Dp) {
    RipDpiStyleProperties.minHeight.provide(height)
}
