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
    val horizontalPadding: Dp = 20.dp,
    val dialogMaxWidth: Dp = 560.dp,
    val snackbarMaxWidth: Dp = 560.dp,
    val cardPadding: Dp = 16.dp,
    val sectionGap: Dp = 20.dp,
    val appBarMinHeight: Dp = 56.dp,
    val bottomBarHeight: Dp = 64.dp,
)

@Immutable
data class RipDpiComponentMetrics(
    val controlHeight: Dp = 48.dp,
    val compactCornerRadius: Dp = 8.dp,
    val mediumCornerRadius: Dp = 10.dp,
    val largeCornerRadius: Dp = 12.dp,
    val controlCornerRadius: Dp = 16.dp,
    val cardCornerRadius: Dp = 16.dp,
    val chipCornerRadius: Dp = 12.dp,
    val pillCornerRadius: Dp = 28.dp,
    val buttonMinHeight: Dp = 48.dp,
    val buttonHorizontalPadding: Dp = 20.dp,
    val buttonVerticalPadding: Dp = 10.dp,
    val fieldHorizontalPadding: Dp = 17.dp,
    val fieldFocusedHorizontalPadding: Dp = 18.dp,
    val multilineFieldMinHeight: Dp = 96.dp,
    val chipHorizontalPadding: Dp = 17.dp,
    val chipVerticalPadding: Dp = 6.dp,
    val iconButtonSize: Dp = 48.dp,
    val switchWidth: Dp = 52.dp,
    val switchHeight: Dp = 48.dp,
    val switchTrackHeight: Dp = 32.dp,
    val switchThumbSize: Dp = 24.dp,
    val switchThumbPadding: Dp = 4.dp,
    val dialogIconSize: Dp = 40.dp,
    val decorativeBadgeSize: Dp = 28.dp,
    val compactPillHorizontalPadding: Dp = 8.dp,
    val compactPillVerticalPadding: Dp = 2.dp,
    val settingsRowVerticalPadding: Dp = 14.dp,
    val sheetHandleWidth: Dp = 36.dp,
    val sheetHandleHeight: Dp = 4.dp,
    val settingsRowMinHeight: Dp = 52.dp,
    val settingsRowMinHeightWithSubtitle: Dp = 68.dp,
    val bottomNavIndicatorWidth: Dp = 52.dp,
    val bottomNavIndicatorHeight: Dp = 28.dp,
)

object RipDpiStroke {
    val Thin = 1.dp
    val Hairline = 0.5.dp
}

val DefaultRipDpiSpacing = RipDpiSpacing()
val DefaultRipDpiLayout = RipDpiLayout()
val DefaultRipDpiComponentMetrics = RipDpiComponentMetrics()

internal val LocalRipDpiSpacing = staticCompositionLocalOf { DefaultRipDpiSpacing }
internal val LocalRipDpiLayout = staticCompositionLocalOf { DefaultRipDpiLayout }
internal val LocalRipDpiComponentMetrics = staticCompositionLocalOf { DefaultRipDpiComponentMetrics }
