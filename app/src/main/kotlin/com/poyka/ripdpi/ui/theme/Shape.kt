package com.poyka.ripdpi.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape

@Immutable
data class RipDpiShapeTokens(
    val xs: Shape = RoundedCornerShape(DefaultRipDpiComponents.shapes.extraSmallCornerRadius),
    val sm: Shape = RoundedCornerShape(DefaultRipDpiComponents.shapes.compactCornerRadius),
    val md: Shape = RoundedCornerShape(DefaultRipDpiComponents.shapes.mediumCornerRadius),
    val lg: Shape = RoundedCornerShape(DefaultRipDpiComponents.shapes.largeCornerRadius),
    val xl: Shape = RoundedCornerShape(DefaultRipDpiComponents.shapes.controlCornerRadius),
    val xlIncreased: Shape = RoundedCornerShape(DefaultRipDpiComponents.shapes.controlIncreasedCornerRadius),
    val xxl: Shape = RoundedCornerShape(DefaultRipDpiComponents.shapes.pillCornerRadius),
    val xxlIncreased: Shape = RoundedCornerShape(DefaultRipDpiComponents.shapes.pillIncreasedCornerRadius),
    val xxxl: Shape = RoundedCornerShape(DefaultRipDpiComponents.shapes.heroCornerRadius),
    val full: Shape = CircleShape,
)

val DefaultRipDpiShapes = RipDpiShapeTokens()

val RipDpiShapes =
    Shapes(
        extraSmall = RoundedCornerShape(DefaultRipDpiComponents.shapes.compactCornerRadius),
        small = RoundedCornerShape(DefaultRipDpiComponents.shapes.mediumCornerRadius),
        medium = RoundedCornerShape(DefaultRipDpiComponents.shapes.largeCornerRadius),
        large = RoundedCornerShape(DefaultRipDpiComponents.shapes.controlCornerRadius),
        extraLarge = RoundedCornerShape(DefaultRipDpiComponents.shapes.pillCornerRadius),
    )

internal val LocalRipDpiShapes = staticCompositionLocalOf { DefaultRipDpiShapes }
