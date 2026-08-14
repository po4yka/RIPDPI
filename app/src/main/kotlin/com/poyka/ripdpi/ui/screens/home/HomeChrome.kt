package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.RipDpiWidthClass

@Immutable
internal data class HomeChromeMetrics(
    val topBarMinHeight: Dp = 72.dp,
    val connectionHaloSize: Dp = 192.dp,
    val connectionButtonSize: Dp = 172.dp,
    val connectionHorizontalPadding: Dp = 20.dp,
    val connectionVerticalPadding: Dp = 24.dp,
    val connectionIconSize: Dp = 28.dp,
)

@Composable
internal fun rememberHomeChromeMetrics(): HomeChromeMetrics {
    val layout = RipDpiThemeTokens.layout

    return when (layout.widthClass) {
        RipDpiWidthClass.Compact -> {
            HomeChromeMetrics()
        }

        RipDpiWidthClass.Medium -> {
            HomeChromeMetrics(
                topBarMinHeight = 76.dp,
                connectionHaloSize = 204.dp,
                connectionButtonSize = 184.dp,
                connectionHorizontalPadding = 24.dp,
                connectionVerticalPadding = 26.dp,
                connectionIconSize = 30.dp,
            )
        }

        RipDpiWidthClass.Expanded -> {
            HomeChromeMetrics(
                topBarMinHeight = 80.dp,
                connectionHaloSize = 216.dp,
                connectionButtonSize = 196.dp,
                connectionHorizontalPadding = 28.dp,
                connectionVerticalPadding = 28.dp,
                connectionIconSize = 32.dp,
            )
        }
    }
}

@Composable
internal fun HomeTopBar(
    title: String,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val layout = RipDpiThemeTokens.layout
    val type = RipDpiThemeTokens.type
    val homeChrome = rememberHomeChromeMetrics()
    val containerMaxWidth = layout.contentMaxWidth + layout.horizontalPadding + layout.horizontalPadding

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = layout.horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .widthIn(max = containerMaxWidth)
                    .fillMaxWidth()
                    .heightIn(min = homeChrome.topBarMinHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = type.brandMark,
                color = colors.foreground,
            )
        }
    }
}
