package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.cards.SettingsRowVariant
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/** The Settings destination open in the detail pane beside the Settings list, or null in one-pane layouts. */
val LocalSelectedSettingsDetail = staticCompositionLocalOf<Route?> { null }

/** Highlights and marks selected a Settings row while the destination it opens shows beside the list. */
@Composable
internal fun settingsDetailVariant(route: Route): SettingsRowVariant =
    if (LocalSelectedSettingsDetail.current == route) SettingsRowVariant.Selected else SettingsRowVariant.Default

/** Detail pane content while no Settings item is selected. */
@Composable
fun SettingsDetailPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .ripDpiTestTag(RipDpiTestTags.SettingsDetailPlaceholder)
                .padding(RipDpiThemeTokens.layout.horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.settings_detail_placeholder),
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.mutedForeground,
            textAlign = TextAlign.Center,
        )
    }
}
