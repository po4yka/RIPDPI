package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

data class RipDpiTab(
    val key: String,
    val label: String,
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
    tabs: List<RipDpiTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    TabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier,
        containerColor = colors.card,
        contentColor = colors.foreground,
        indicator = { tabPositions ->
            if (selectedIndex in tabPositions.indices) {
                SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                    height = 2.dp,
                    color = colors.foreground,
                )
            }
        },
        divider = {},
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = index == selectedIndex
            Tab(
                selected = isSelected,
                onClick = { onSelect(index) },
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
private fun RipDpiTabsPreviewLight() {
    RipDpiComponentPreview {
        RipDpiTabs(
            tabs =
                listOf(
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
private fun RipDpiTabsPreviewDark() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiTabs(
            tabs = listOf(RipDpiTab("a", "Live"), RipDpiTab("b", "History")),
            selectedIndex = 0,
            onSelect = {},
        )
    }
}
