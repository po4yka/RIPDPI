package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.cards.SettingsRowVariant
import com.poyka.ripdpi.ui.components.scaffold.RipDpiDetailPaneEmptyState
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

/**
 * Scroll state of the Settings list, owned above the Settings list pane so it survives the pane leaving
 * composition (tab switch) and the list moving between the Settings destination and the list pane.
 */
val LocalSettingsListState = staticCompositionLocalOf<LazyListState?> { null }

/** The Settings destination open in the detail pane beside the Settings list, or null in one-pane layouts. */
val LocalSelectedSettingsDetail = staticCompositionLocalOf<Route?> { null }

/** Highlights and marks selected a Settings row while the destination it opens shows beside the list. */
@Composable
internal fun settingsDetailVariant(route: Route): SettingsRowVariant =
    if (LocalSelectedSettingsDetail.current == route) SettingsRowVariant.Selected else SettingsRowVariant.Default

/** Detail pane content while no Settings item is selected. */
@Composable
fun SettingsDetailPlaceholder(modifier: Modifier = Modifier) {
    RipDpiDetailPaneEmptyState(
        body = stringResource(R.string.settings_detail_placeholder),
        testTag = RipDpiTestTags.SettingsDetailPlaceholder,
        modifier = modifier,
    )
}
