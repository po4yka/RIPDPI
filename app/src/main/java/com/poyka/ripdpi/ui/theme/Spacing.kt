package com.poyka.ripdpi.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class RipDpiSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
    val section: Dp = 40.dp,
    val screen: Dp = 48.dp,
)

@Immutable
data class RipDpiLayout(
    val mobileFrameWidth: Dp = 360.dp,
    val frameHeight: Dp = 800.dp,
    val horizontalPadding: Dp = 20.dp,
    val safeContentWidth: Dp = 320.dp,
    val cardPadding: Dp = 16.dp,
    val sectionGap: Dp = 20.dp,
    val appBarHeight: Dp = 48.dp,
    val bottomBarHeight: Dp = 64.dp,
    val statusBarHeight: Dp = 28.dp,
)

object RipDpiStroke {
    val Thin = 1.dp
    val Hairline = 0.5.dp
}

val DefaultRipDpiSpacing = RipDpiSpacing()
val DefaultRipDpiLayout = RipDpiLayout()

internal val LocalRipDpiSpacing = staticCompositionLocalOf { DefaultRipDpiSpacing }
internal val LocalRipDpiLayout = staticCompositionLocalOf { DefaultRipDpiLayout }
