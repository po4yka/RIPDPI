package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Immutable
internal data class HomeChromeMetrics(
    val topBarMinHeight: Dp = 64.dp,
    val brandBadgeSize: Dp = 32.dp,
    val connectionHaloSize: Dp = 216.dp,
    val connectionButtonSize: Dp = 172.dp,
    val connectionHorizontalPadding: Dp = 20.dp,
    val connectionVerticalPadding: Dp = 24.dp,
    val connectionIconSize: Dp = 28.dp,
)

internal val DefaultHomeChromeMetrics = HomeChromeMetrics()

@Composable
internal fun HomeTopBar(
    title: String,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val homeChrome = DefaultHomeChromeMetrics

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = homeChrome.topBarMinHeight)
                .padding(horizontal = layout.horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(homeChrome.brandBadgeSize)
                    .background(colors.foreground, RipDpiThemeTokens.shapes.full),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title.take(1),
                style = type.smallLabel,
                color = colors.background,
                maxLines = 1,
            )
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = type.brandMark,
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
