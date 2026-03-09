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
    val appBarHeight: Dp = 48.dp,
    val bottomBarHeight: Dp = 64.dp,
)

@Immutable
data class RipDpiComponentMetrics(
    val controlHeight: Dp = 40.dp,
    val compactCornerRadius: Dp = 8.dp,
    val mediumCornerRadius: Dp = 10.dp,
    val largeCornerRadius: Dp = 12.dp,
    val controlCornerRadius: Dp = 16.dp,
    val cardCornerRadius: Dp = 16.dp,
    val chipCornerRadius: Dp = 12.dp,
    val pillCornerRadius: Dp = 28.dp,
    val buttonMinHeight: Dp = 40.dp,
    val buttonHorizontalPadding: Dp = 20.dp,
    val buttonVerticalPadding: Dp = 10.dp,
    val fieldHorizontalPadding: Dp = 17.dp,
    val fieldFocusedHorizontalPadding: Dp = 18.dp,
    val multilineFieldMinHeight: Dp = 96.dp,
    val chipHorizontalPadding: Dp = 17.dp,
    val chipVerticalPadding: Dp = 6.dp,
    val iconButtonSize: Dp = 40.dp,
    val switchWidth: Dp = 44.dp,
    val switchHeight: Dp = 24.dp,
    val switchThumbSize: Dp = 20.dp,
    val switchThumbPadding: Dp = 2.dp,
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
    val homeConnectionHaloSize: Dp = 216.dp,
    val homeConnectionButtonSize: Dp = 172.dp,
    val homeConnectionHorizontalPadding: Dp = 20.dp,
    val homeConnectionVerticalPadding: Dp = 24.dp,
    val homeConnectionIconSize: Dp = 28.dp,
)

@Immutable
data class RipDpiIntroLayout(
    val topActionRowHeight: Dp = 37.dp,
    val topActionTopPadding: Dp = 16.dp,
    val illustrationSize: Dp = 64.dp,
    val illustrationCornerRadius: Dp = 16.dp,
    val illustrationBorderWidth: Dp = 1.5.dp,
    val illustrationIconSize: Dp = 24.dp,
    val illustrationIconStrokeWidth: Dp = 2.dp,
    val illustrationToTitleGap: Dp = 40.dp,
    val titleToBodyGap: Dp = 16.dp,
    val bodyToContentGap: Dp = 24.dp,
    val titleHorizontalPadding: Dp = 12.dp,
    val bodyHorizontalPadding: Dp = 14.dp,
    val indicatorSize: Dp = 8.dp,
    val indicatorActiveWidth: Dp = 24.dp,
    val indicatorSpacing: Dp = 8.dp,
    val footerProgressGap: Dp = 28.dp,
    val footerButtonHorizontalInset: Dp = 18.dp,
    val footerButtonMinHeight: Dp = 52.dp,
    val footerBottomPadding: Dp = 40.dp,
)

object RipDpiStroke {
    val Thin = 1.dp
    val Hairline = 0.5.dp
}

val DefaultRipDpiSpacing = RipDpiSpacing()
val DefaultRipDpiLayout = RipDpiLayout()
val DefaultRipDpiComponentMetrics = RipDpiComponentMetrics()
val DefaultRipDpiIntroLayout = RipDpiIntroLayout()

internal val LocalRipDpiSpacing = staticCompositionLocalOf { DefaultRipDpiSpacing }
internal val LocalRipDpiLayout = staticCompositionLocalOf { DefaultRipDpiLayout }
internal val LocalRipDpiComponentMetrics = staticCompositionLocalOf { DefaultRipDpiComponentMetrics }
internal val LocalRipDpiIntroLayout = staticCompositionLocalOf { DefaultRipDpiIntroLayout }
