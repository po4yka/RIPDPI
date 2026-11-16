@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.lerp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.intro.rememberRipDpiIntroScaffoldMetrics
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.pageIndicator
import com.poyka.ripdpi.ui.theme.rememberRipDpiStyleResolver
import com.poyka.ripdpi.ui.theme.ripDpiStyleSurface
import kotlin.math.abs

@Composable
@NonRestartableComposable
fun RipDpiPageIndicators(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
    accessibilityLabel: String? = null,
    pagePosition: (() -> Float)? = null,
) {
    RipDpiPageIndicators(
        currentPage = currentPage,
        pageCount = pageCount,
        modifier = modifier,
        accessibilityLabel = accessibilityLabel,
        visualStyle = CommonStyle,
        pagePosition = pagePosition,
    )
}

@Composable
fun RipDpiPageIndicators(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
    accessibilityLabel: String? = null,
    visualStyle: CommonStyle = CommonStyle,
    pagePosition: (() -> Float)? = null,
) {
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    val motion = RipDpiThemeTokens.motion

    Row(
        modifier =
            modifier.then(
                if (accessibilityLabel != null) {
                    // Polite live region: TalkBack announces the label when the page changes.
                    Modifier.semantics {
                        contentDescription = accessibilityLabel
                        liveRegion = LiveRegionMode.Polite
                    }
                } else {
                    Modifier
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(introLayout.indicatorSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount.coerceAtLeast(0)) { index ->
            val selected = index == currentPage
            val styleState = rememberUpdatedStyleState(null) { it.isSelected = selected }
            val resolver = rememberRipDpiStyleResolver(RipDpiThemeTokens.styles.pageIndicator, visualStyle, styleState)
            val sizeModifier =
                if (pagePosition != null) {
                    // The width follows a fractional pager position. It is read in the layout phase, so a drag
                    // lays the row out again and does not recompose it.
                    Modifier.layout { measurable, _ ->
                        val weight = (1f - abs(pagePosition() - index)).coerceIn(0f, 1f)
                        val width = lerp(introLayout.indicatorSize, introLayout.indicatorActiveWidth, weight)
                        val placeable =
                            measurable.measure(
                                Constraints.fixed(width.roundToPx(), introLayout.indicatorSize.roundToPx()),
                            )
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                } else {
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
                    Modifier.size(width = width, height = introLayout.indicatorSize)
                }
            Box(
                modifier =
                    sizeModifier.ripDpiStyleSurface(resolver, shape = RipDpiThemeTokens.shapes.full),
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
private fun RipDpiPageIndicatorsBetweenPagesPreview() {
    RipDpiComponentPreview {
        RipDpiPageIndicators(currentPage = 1, pageCount = 4, pagePosition = { 1.5f })
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiPageIndicatorsBetweenPagesDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiPageIndicators(currentPage = 1, pageCount = 4, pagePosition = { 1.5f })
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiPageIndicatorsDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiPageIndicators(currentPage = 2, pageCount = 4)
    }
}
