package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.poyka.ripdpi.ui.components.scaffold.RipDpiAdaptiveColumns

/**
 * Home's two-column split for Expanded windows.
 *
 * The Expanded tier has declared `SplitColumns` since the layout tiers were
 * written and no screen ever branched into it, so a 1040dp window rendered one
 * tall column with an empty lower half. Below Expanded this stacks primary then
 * secondary in order, so narrower layouts render identically.
 *
 * Kept in its own file so [HomeScreen] does not also carry layout-strategy
 * concerns on top of the feature areas it already coordinates.
 */
@Composable
internal fun HomeContentColumns(
    primary: @Composable ColumnScope.() -> Unit,
    secondary: @Composable ColumnScope.() -> Unit,
) {
    RipDpiAdaptiveColumns(primary = primary, secondary = secondary)
}
