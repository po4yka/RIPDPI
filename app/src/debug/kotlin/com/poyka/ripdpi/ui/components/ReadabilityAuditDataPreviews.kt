package com.poyka.ripdpi.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.screens.diagnostics.StateMachineRoute
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Preview(name = "State diagram light", widthDp = 420, heightDp = 750, locale = "ru")
@Preview(
    name = "State diagram dark",
    widthDp = 420,
    heightDp = 750,
    locale = "ru",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "State diagram large font", widthDp = 320, heightDp = 1300, locale = "ru", fontScale = 2f)
@Composable
private fun ReadabilityAuditStateDiagramPreview() {
    DataReadabilityPreview {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(RipDpiThemeTokens.spacing.lg)) {
            StateMachineRoute()
        }
    }
}

@Composable
private fun DataReadabilityPreview(content: @Composable () -> Unit) {
    RipDpiTheme(themePreference = if (isSystemInDarkTheme()) "dark" else "light") {
        Surface(modifier = Modifier.fillMaxSize(), color = RipDpiThemeTokens.colors.background, content = content)
    }
}
