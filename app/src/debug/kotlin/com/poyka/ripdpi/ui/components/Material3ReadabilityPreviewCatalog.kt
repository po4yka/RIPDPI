package com.poyka.ripdpi.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.theme.RipDpiIcons

@Preview(name = "Home light", widthDp = 420, heightDp = 900, locale = "ru")
@Preview(
    name = "Home dark",
    widthDp = 420,
    heightDp = 900,
    locale = "ru",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "Home large font", widthDp = 420, heightDp = 900, locale = "ru", fontScale = 2f)
@Composable
private fun Material3ReadabilityHomePreview() {
    RipDpiHomeDisconnectedPreviewScene(themePreference = readabilityTheme())
}

@Preview(name = "Settings light", widthDp = 420, heightDp = 900, locale = "ru")
@Preview(
    name = "Settings dark",
    widthDp = 420,
    heightDp = 900,
    locale = "ru",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "Settings large font", widthDp = 420, heightDp = 900, locale = "ru", fontScale = 2f)
@Composable
private fun Material3ReadabilitySettingsPreview() {
    RipDpiSettingsMediumPreviewScene(themePreference = readabilityTheme())
}

@Preview(name = "Diagnostics light", widthDp = 420, heightDp = 900, locale = "ru")
@Preview(
    name = "Diagnostics dark",
    widthDp = 420,
    heightDp = 900,
    locale = "ru",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "Diagnostics large font", widthDp = 420, heightDp = 900, locale = "ru", fontScale = 2f)
@Composable
private fun Material3ReadabilityDiagnosticsPreview() {
    RipDpiDiagnosticsScanPreviewScene(themePreference = readabilityTheme())
}

@Preview(name = "Connection health light", widthDp = 420, heightDp = 220, locale = "ru")
@Preview(
    name = "Connection health dark",
    widthDp = 420,
    heightDp = 220,
    locale = "ru",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "Connection health large font", widthDp = 420, heightDp = 460, locale = "ru", fontScale = 2f)
@Composable
private fun Material3ReadabilityConnectionHealthPreview() {
    RipDpiComponentPreview(themePreference = readabilityTheme()) {
        RipDpiCard {
            SettingsRow(
                title = stringResource(R.string.connection_health_home_title),
                subtitle = stringResource(R.string.connection_health_home_subtitle),
                value = stringResource(R.string.connection_health_home_value),
                onClick = {},
                leadingIcon = RipDpiIcons.NetworkCheck,
                showChevron = true,
            )
        }
    }
}

@Composable
private fun readabilityTheme(): String = if (isSystemInDarkTheme()) "dark" else "light"
