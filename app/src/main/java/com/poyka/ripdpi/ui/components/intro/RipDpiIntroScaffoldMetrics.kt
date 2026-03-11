package com.poyka.ripdpi.ui.components.intro

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
internal data class RipDpiIntroScaffoldMetrics(
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

internal val DefaultRipDpiIntroScaffoldMetrics = RipDpiIntroScaffoldMetrics()
