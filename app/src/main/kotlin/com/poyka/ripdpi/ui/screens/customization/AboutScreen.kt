package com.poyka.ripdpi.ui.screens.customization

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun AboutRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AboutScreen(
        onBack = onBack,
        modifier = modifier,
    )
}

@Suppress("LongMethod")
@Composable
internal fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val type = RipDpiThemeTokens.type
    val uriHandler = LocalUriHandler.current
    val sourceUrl = stringResource(R.string.ripdpi_source)
    val docsUrl = stringResource(R.string.ripdpi_docs)

    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.About))
                .fillMaxSize()
                .background(colors.background),
        title = stringResource(R.string.about_category),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        item {
            RipDpiCard {
                Text(
                    text = stringResource(R.string.app_name),
                    style = type.screenTitle,
                    color = colors.foreground,
                )
                Text(
                    text = stringResource(R.string.splash_subtitle),
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                )
                Text(
                    text = stringResource(R.string.about_description),
                    style = type.body,
                    color = colors.mutedForeground,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                SettingsCategoryHeader(title = stringResource(R.string.about_build_section))
                RipDpiCard(paddingValues = PaddingValues(horizontal = layout.cardPadding, vertical = 0.dp)) {
                    SettingsRow(
                        title = stringResource(R.string.version),
                        value = BuildConfig.VERSION_NAME,
                        showDivider = true,
                    )
                    SettingsRow(
                        title = stringResource(R.string.about_version_code),
                        value = BuildConfig.VERSION_CODE.toString(),
                        showDivider = true,
                    )
                    SettingsRow(
                        title = stringResource(R.string.about_build_type),
                        value = BuildConfig.BUILD_TYPE,
                        showDivider = true,
                    )
                    SettingsRow(
                        title = stringResource(R.string.about_package_name),
                        value = BuildConfig.APPLICATION_ID,
                        monospaceValue = true,
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                SettingsCategoryHeader(title = stringResource(R.string.about_project_section))
                RipDpiCard {
                    Text(
                        text = stringResource(R.string.about_credits_title),
                        style = type.bodyEmphasis,
                        color = colors.foreground,
                    )
                    Text(
                        text = stringResource(R.string.about_credits_body),
                        style = type.body,
                        color = colors.mutedForeground,
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                SettingsCategoryHeader(title = stringResource(R.string.about_links_section))
                RipDpiCard {
                    RipDpiButton(
                        text = stringResource(R.string.source_code_link),
                        onClick = { uriHandler.openUri(sourceUrl) },
                        variant = RipDpiButtonVariant.Outline,
                        leadingIcon = RipDpiIcons.Share,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .ripDpiTestTag(RipDpiTestTags.AboutSourceCode),
                    )
                    RipDpiButton(
                        text = stringResource(R.string.ripdpi_readme_link),
                        onClick = { uriHandler.openUri(docsUrl) },
                        variant = RipDpiButtonVariant.Ghost,
                        leadingIcon = RipDpiIcons.Info,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .ripDpiTestTag(RipDpiTestTags.AboutReadme),
                    )
                }
            }
        }
    }
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun AboutScreenPreview() {
    RipDpiTheme(themePreference = "light") {
        AboutScreen(onBack = {})
    }
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun AboutScreenDarkPreview() {
    RipDpiTheme(themePreference = "dark") {
        AboutScreen(onBack = {})
    }
}
