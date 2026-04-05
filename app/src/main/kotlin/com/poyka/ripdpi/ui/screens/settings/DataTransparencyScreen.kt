package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun DataTransparencyRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DataTransparencyScreen(
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun DataTransparencyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.DataTransparency))
                .fillMaxSize()
                .background(colors.background),
        title = stringResource(R.string.title_data_transparency),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        item {
            TransparencySection(
                title = stringResource(R.string.data_transparency_what_we_collect_section),
            ) {
                BulletItem(stringResource(R.string.data_transparency_network_diagnostics))
                BulletItem(stringResource(R.string.data_transparency_dpi_detection))
                BulletItem(stringResource(R.string.data_transparency_performance_metrics))
                BulletItem(stringResource(R.string.data_transparency_network_identity))
                BulletItem(stringResource(R.string.data_transparency_device_context))
                BulletItem(stringResource(R.string.data_transparency_optional_ip))
            }
        }

        item {
            TransparencySection(
                title = stringResource(R.string.data_transparency_what_we_do_not_collect_section),
            ) {
                BulletItem(stringResource(R.string.data_transparency_no_browsing))
                BulletItem(stringResource(R.string.data_transparency_no_personal_data))
                BulletItem(stringResource(R.string.data_transparency_no_external_servers))
                BulletItem(stringResource(R.string.data_transparency_no_analytics))
            }
        }

        item {
            TransparencySection(
                title = stringResource(R.string.data_transparency_how_stored_section),
            ) {
                BulletItem(stringResource(R.string.data_transparency_local_database))
                BulletItem(stringResource(R.string.data_transparency_retention_period))
                BulletItem(stringResource(R.string.data_transparency_disable_monitoring))
                BulletItem(stringResource(R.string.data_transparency_export_explicit))
            }
        }

        item {
            TransparencySection(
                title = stringResource(R.string.data_transparency_export_privacy_section),
            ) {
                BulletItem(stringResource(R.string.data_transparency_export_redaction))
                BulletItem(stringResource(R.string.data_transparency_export_control))
            }
        }
    }
}

@Composable
private fun TransparencySection(
    title: String,
    content: @Composable () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = title)
        RipDpiCard(content = { content() })
    }
}

@Composable
private fun BulletItem(text: String) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "\u2022",
            style = type.body,
            color = colors.mutedForeground,
            modifier = Modifier.padding(top = 1.dp),
        )
        Text(
            text = text,
            style = type.body,
            color = colors.mutedForeground,
            modifier = Modifier.weight(1f),
        )
    }
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun DataTransparencyScreenPreview() {
    RipDpiTheme(themePreference = "light") {
        DataTransparencyScreen(onBack = {})
    }
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun DataTransparencyScreenDarkPreview() {
    RipDpiTheme(themePreference = "dark") {
        DataTransparencyScreen(onBack = {})
    }
}
