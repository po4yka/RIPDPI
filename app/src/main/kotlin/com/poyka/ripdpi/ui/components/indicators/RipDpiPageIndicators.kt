package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.intro.rememberRipDpiIntroScaffoldMetrics
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun RipDpiPageIndicators(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val introLayout = rememberRipDpiIntroScaffoldMetrics()

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(introLayout.indicatorSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount.coerceAtLeast(0)) { index ->
            val selected = index == currentPage
            val width by animateDpAsState(
                targetValue =
                    if (selected) {
                        introLayout.indicatorActiveWidth
                    } else {
                        introLayout.indicatorSize
                    },
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "pageIndicatorWidth",
            )
            val color by animateColorAsState(
                targetValue = if (selected) colors.foreground else colors.border,
                label = "pageIndicatorColor",
            )
            Box(
                modifier =
                    Modifier
                        .size(width = width, height = introLayout.indicatorSize)
                        .background(color, RipDpiThemeTokens.shapes.full),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiPageIndicatorsPreview() {
    RipDpiComponentPreview {
        RipDpiPageIndicators(currentPage = 1, pageCount = 3)
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiPageIndicatorsDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiPageIndicators(currentPage = 2, pageCount = 4)
    }
}
