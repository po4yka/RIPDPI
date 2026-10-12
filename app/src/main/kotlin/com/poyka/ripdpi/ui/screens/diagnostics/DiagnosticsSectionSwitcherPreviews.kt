package com.poyka.ripdpi.ui.screens.diagnostics

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Preview(name = "Diagnostics tabs light", widthDp = 320, heightDp = 100, locale = "ru")
@Preview(
    name = "Diagnostics tabs dark",
    widthDp = 320,
    heightDp = 100,
    locale = "ru",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "Diagnostics tabs font 2", widthDp = 320, heightDp = 120, fontScale = 2f, locale = "ru")
@Composable
private fun DiagnosticsSectionSwitcherPreview() {
    DiagnosticsTabsScene()
}

@Preview(name = "Diagnostics tabs RTL font 2", widthDp = 320, heightDp = 120, fontScale = 2f, locale = "ar")
@Composable
private fun DiagnosticsSectionSwitcherRtlPreview() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        DiagnosticsTabsScene()
    }
}

@Composable
private fun DiagnosticsTabsScene() {
    RipDpiTheme {
        Column(Modifier.fillMaxSize().background(RipDpiThemeTokens.colors.background)) {
            DiagnosticsSectionSwitcher(
                selectedSection = DiagnosticsSection.Dashboard,
                onSelectSection = {},
            )
        }
    }
}
