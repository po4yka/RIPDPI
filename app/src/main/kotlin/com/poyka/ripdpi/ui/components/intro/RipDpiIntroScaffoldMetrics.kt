package com.poyka.ripdpi.ui.components.intro

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.RipDpiWidthClass

@Immutable
internal data class RipDpiIntroScaffoldMetrics(
    val topActionRowHeight: Dp = 48.dp,
    val illustrationSize: Dp = 80.dp,
    val illustrationCornerRadius: Dp = 16.dp,
    val illustrationBorderWidth: Dp = 1.5.dp,
    val illustrationIconSize: Dp = 32.dp,
    val illustrationIconStrokeWidth: Dp = 2.dp,
    val illustrationToTitleGap: Dp = 28.dp,
    val titleToBodyGap: Dp = 16.dp,
    val bodyToContentGap: Dp = 24.dp,
    // Vertical gap between a setup page's header (title/subtitle) and its content block.
    val setupHeaderToContentGap: Dp = 20.dp,
    val titleHorizontalPadding: Dp = 12.dp,
    val bodyHorizontalPadding: Dp = 14.dp,
    val indicatorSize: Dp = 8.dp,
    val indicatorActiveWidth: Dp = 24.dp,
    val indicatorSpacing: Dp = 8.dp,
    val footerProgressGap: Dp = 20.dp,
    val footerButtonHorizontalInset: Dp = 18.dp,
    val footerButtonMinHeight: Dp = 52.dp,
    // Padding ABOVE the Android navigation-bar inset. Kept modest so the bottom action area is not
    // pushed up by an iOS-home-indicator-sized gap; the real safe area comes from navigationBarsPadding.
    val footerBottomPadding: Dp = 20.dp,
)

@Composable
internal fun rememberRipDpiIntroScaffoldMetrics(): RipDpiIntroScaffoldMetrics {
    val layout = RipDpiThemeTokens.layout

    return when (layout.widthClass) {
        RipDpiWidthClass.Compact -> {
            RipDpiIntroScaffoldMetrics()
        }

        RipDpiWidthClass.Medium -> {
            RipDpiIntroScaffoldMetrics(
                topActionRowHeight = 48.dp,
                illustrationSize = 96.dp,
                illustrationToTitleGap = 36.dp,
                setupHeaderToContentGap = 24.dp,
                titleHorizontalPadding = 20.dp,
                bodyHorizontalPadding = 24.dp,
                footerButtonHorizontalInset = 0.dp,
                footerBottomPadding = 24.dp,
            )
        }

        RipDpiWidthClass.Expanded -> {
            RipDpiIntroScaffoldMetrics(
                topActionRowHeight = 52.dp,
                illustrationSize = 104.dp,
                illustrationToTitleGap = 40.dp,
                setupHeaderToContentGap = 28.dp,
                titleHorizontalPadding = 24.dp,
                bodyHorizontalPadding = 28.dp,
                footerButtonHorizontalInset = 0.dp,
                footerBottomPadding = 28.dp,
            )
        }
    }
}
