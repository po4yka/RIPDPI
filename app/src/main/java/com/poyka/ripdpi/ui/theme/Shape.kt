package com.poyka.ripdpi.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

@Immutable
data class RipDpiShapeTokens(
    val sm: Shape = RoundedCornerShape(8.dp),
    val md: Shape = RoundedCornerShape(10.dp),
    val lg: Shape = RoundedCornerShape(12.dp),
    val xl: Shape = RoundedCornerShape(16.dp),
    val xxl: Shape = RoundedCornerShape(28.dp),
    val full: Shape = CircleShape,
)

val DefaultRipDpiShapes = RipDpiShapeTokens()

val RipDpiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

internal val LocalRipDpiShapes = staticCompositionLocalOf { DefaultRipDpiShapes }
