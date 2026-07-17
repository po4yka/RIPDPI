package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
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
