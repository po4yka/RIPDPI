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
                illustrationToTitleGap = 48.dp,
                titleHorizontalPadding = 20.dp,
                bodyHorizontalPadding = 24.dp,
                footerButtonHorizontalInset = 0.dp,
                footerBottomPadding = 44.dp,
            )
        }

        RipDpiWidthClass.Expanded -> {
            RipDpiIntroScaffoldMetrics(
                topActionRowHeight = 52.dp,
                illustrationSize = 104.dp,
                illustrationToTitleGap = 52.dp,
                titleHorizontalPadding = 24.dp,
                bodyHorizontalPadding = 28.dp,
                footerButtonHorizontalInset = 0.dp,
                footerBottomPadding = 48.dp,
            )
        }
    }
}
