package com.poyka.ripdpi.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape

@Immutable
data class RipDpiShapeTokens(
    val sm: Shape = RoundedCornerShape(DefaultRipDpiComponentMetrics.compactCornerRadius),
    val md: Shape = RoundedCornerShape(DefaultRipDpiComponentMetrics.mediumCornerRadius),
    val lg: Shape = RoundedCornerShape(DefaultRipDpiComponentMetrics.largeCornerRadius),
    val xl: Shape = RoundedCornerShape(DefaultRipDpiComponentMetrics.controlCornerRadius),
    val xxl: Shape = RoundedCornerShape(DefaultRipDpiComponentMetrics.pillCornerRadius),
    val full: Shape = CircleShape,
)

val DefaultRipDpiShapes = RipDpiShapeTokens()

val RipDpiShapes =
    Shapes(
        extraSmall = RoundedCornerShape(DefaultRipDpiComponentMetrics.compactCornerRadius),
        small = RoundedCornerShape(DefaultRipDpiComponentMetrics.mediumCornerRadius),
        medium = RoundedCornerShape(DefaultRipDpiComponentMetrics.largeCornerRadius),
        large = RoundedCornerShape(DefaultRipDpiComponentMetrics.controlCornerRadius),
        extraLarge = RoundedCornerShape(DefaultRipDpiComponentMetrics.pillCornerRadius),
    )

internal val LocalRipDpiShapes = staticCompositionLocalOf { DefaultRipDpiShapes }
