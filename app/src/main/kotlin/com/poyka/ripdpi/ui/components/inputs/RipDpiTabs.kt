package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class RipDpiTab(
    val key: String,
    val label: String,
    val testTag: String? = null,
)

/**
 * Branded TabRow: thin foreground indicator on a card-tinted track,
 * compact horizontal padding, selected tab in bodyEmphasis +
 * foreground color, unselected in secondaryBody + mutedForeground.
 *
 * Matches `components-tabs.html`.
 */
@Composable
fun RipDpiTabs(
    tabs: ImmutableList<RipDpiTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    SecondaryTabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier,
        containerColor = colors.card,
        contentColor = colors.foreground,
        indicator = {
            SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(selectedIndex),
                height = RipDpiStroke.Thick,
                color = colors.foreground,
            )
        },
        divider = {},
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = index == selectedIndex
            Tab(
                selected = isSelected,
                onClick = { onSelect(index) },
                modifier = Modifier.ripDpiTestTag(tab.testTag),
                selectedContentColor = colors.foreground,
                unselectedContentColor = colors.mutedForeground,
            ) {
                Text(
                    text = tab.label,
                    style =
                        if (isSelected) {
                            RipDpiThemeTokens.type.bodyEmphasis
                        } else {
                            RipDpiThemeTokens.type.secondaryBody
                        },
                    modifier =
                        Modifier.padding(
                            PaddingValues(
                                horizontal = RipDpiThemeTokens.spacing.sm,
                                vertical = RipDpiThemeTokens.spacing.sm,
                            ),
                        ),
                )
            }
        }
    }
}

/**
 * Fades the edge of a scrollable tab row that hides more tabs, so a cut-off tab reads as
 * "scroll for more" instead of a clipped label.
 */
internal fun Modifier.tabOverflowFade(
    scrollState: ScrollState,
    color: Color,
    width: Dp,
): Modifier =
    drawWithContent {
        drawContent()
        val fade = width.toPx().coerceAtMost(size.width / 2)
        val endIsRight = layoutDirection == LayoutDirection.Ltr
        val clear = color.copy(alpha = 0f)
        if (scrollState.canScrollForward) {
            val left = if (endIsRight) size.width - fade else 0f
            val colors = if (endIsRight) listOf(clear, color) else listOf(color, clear)
            drawRect(Brush.horizontalGradient(colors, left, left + fade), Offset(left, 0f), Size(fade, size.height))
        }
        if (scrollState.canScrollBackward) {
            val left = if (endIsRight) 0f else size.width - fade
            val colors = if (endIsRight) listOf(color, clear) else listOf(clear, color)
            drawRect(Brush.horizontalGradient(colors, left, left + fade), Offset(left, 0f), Size(fade, size.height))
        }
    }

@Preview(showBackground = true, name = "RipDpiTabs (light)")
@Composable
private fun RipDpiTabsLightPreview() {
    RipDpiComponentPreview {
        RipDpiTabs(
            tabs =
                persistentListOf(
                    RipDpiTab("home", "Home"),
                    RipDpiTab("logs", "Logs"),
                    RipDpiTab("settings", "Settings"),
                ),
            selectedIndex = 1,
            onSelect = {},
        )
    }
}

@Preview(showBackground = true, name = "RipDpiTabs (dark)")
@Composable
private fun RipDpiTabsDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiTabs(
            tabs = persistentListOf(RipDpiTab("a", "Live"), RipDpiTab("b", "History")),
            selectedIndex = 0,
            onSelect = {},
        )
    }
}
