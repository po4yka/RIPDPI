@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.intro.rememberRipDpiIntroScaffoldMetrics
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.pageIndicator
import com.poyka.ripdpi.ui.theme.rememberRipDpiStyleResolver
import com.poyka.ripdpi.ui.theme.ripDpiStyleSurface

@Composable
@NonRestartableComposable
fun RipDpiPageIndicators(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
    sectionBreakAfter: Int? = null,
    accessibilityLabel: String? = null,
) {
    RipDpiPageIndicators(
        currentPage = currentPage,
        pageCount = pageCount,
        modifier = modifier,
        sectionBreakAfter = sectionBreakAfter,
        accessibilityLabel = accessibilityLabel,
        visualStyle = CommonStyle,
    )
}

@Composable
fun RipDpiPageIndicators(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
    sectionBreakAfter: Int? = null,
    accessibilityLabel: String? = null,
    visualStyle: CommonStyle = CommonStyle,
) {
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    val motion = RipDpiThemeTokens.motion

    Row(
        modifier =
            modifier.then(
                if (accessibilityLabel != null) {
                    Modifier.semantics { contentDescription = accessibilityLabel }
                } else {
                    Modifier
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(introLayout.indicatorSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount.coerceAtLeast(0)) { index ->
            if (sectionBreakAfter != null && index == sectionBreakAfter) {
                Spacer(modifier = Modifier.width(introLayout.indicatorSpacing))
            }
            val selected = index == currentPage
            val styleState = rememberUpdatedStyleState(null) { it.isSelected = selected }
            val resolver = rememberRipDpiStyleResolver(RipDpiThemeTokens.styles.pageIndicator, visualStyle, styleState)
            val width by animateDpAsState(
                targetValue =
                    if (selected) {
                        introLayout.indicatorActiveWidth
                    } else {
                        introLayout.indicatorSize
                    },
                animationSpec = motion.motionAwareSpring(),
                label = "pageIndicatorWidth",
            )
            Box(
                modifier =
                    Modifier
                        .size(width = width, height = introLayout.indicatorSize)
                        .ripDpiStyleSurface(resolver, shape = RipDpiThemeTokens.shapes.full),
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
