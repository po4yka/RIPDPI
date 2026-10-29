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
    val xxl: Shape = RoundedCornerShape(DefaultRipDpiComponents.shapes.pillCornerRadius),
    val xxxl: Shape = RoundedCornerShape(DefaultRipDpiComponents.shapes.heroCornerRadius),
    val full: Shape = CircleShape,
)

val DefaultRipDpiShapes = RipDpiShapeTokens()

/** Full M3 corner scale (4/8/12/16/20/28/32/48dp), reusing the RDS radii that already equal each step. */
val RipDpiShapes =
    Shapes(
        extraSmall = RoundedCornerShape(DefaultRipDpiComponents.shapes.extraSmallCornerRadius),
        small = RoundedCornerShape(DefaultRipDpiComponents.shapes.compactCornerRadius),
        medium = RoundedCornerShape(DefaultRipDpiComponents.shapes.largeCornerRadius),
        large = RoundedCornerShape(DefaultRipDpiComponents.shapes.controlCornerRadius),
        largeIncreased = RoundedCornerShape(DefaultRipDpiComponents.shapes.controlIncreasedCornerRadius),
        extraLarge = RoundedCornerShape(DefaultRipDpiComponents.shapes.pillCornerRadius),
        extraLargeIncreased = RoundedCornerShape(DefaultRipDpiComponents.shapes.pillIncreasedCornerRadius),
        extraExtraLarge = RoundedCornerShape(DefaultRipDpiComponents.shapes.heroCornerRadius),
    )

internal val LocalRipDpiShapes = staticCompositionLocalOf { DefaultRipDpiShapes }
