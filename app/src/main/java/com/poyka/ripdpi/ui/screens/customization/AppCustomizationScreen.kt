package com.poyka.ripdpi.ui.screens.customization

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.LauncherIconManager
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun AppCustomizationRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AppCustomizationScreen(
        uiState = uiState,
        onBack = onBack,
        onIconSelected = viewModel::setAppIcon,
        onThemedIconChanged = viewModel::setThemedAppIconEnabled,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppCustomizationScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onIconSelected: (String) -> Unit,
    onThemedIconChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val selectedOption = LauncherIconManager.resolveOption(uiState.appIconVariant)
    var showAdaptiveShapeSheet by rememberSaveable { mutableStateOf(false) }

    if (showAdaptiveShapeSheet) {
        RipDpiBottomSheet(
            onDismissRequest = { showAdaptiveShapeSheet = false },
            title = stringResource(R.string.customization_shape_sheet_title),
            message = stringResource(R.string.customization_shape_sheet_body),
            icon = RipDpiIcons.Info,
            testTag = RipDpiTestTags.CustomizationShapeInfoSheet,
            primaryActionLabel = stringResource(R.string.customization_shape_sheet_action),
            onPrimaryAction = { showAdaptiveShapeSheet = false },
            primaryActionTestTag = RipDpiTestTags.CustomizationShapeInfoSheetConfirm,
        ) {
            Text(
                text = stringResource(R.string.customization_shape_sheet_point_one),
                style = type.caption,
                color = colors.mutedForeground,
            )
            Text(
                text = stringResource(R.string.customization_shape_sheet_point_two),
                style = type.caption,
                color = colors.mutedForeground,
            )
        }
    }

    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.AppCustomization))
                .fillMaxSize()
                .background(colors.background),
        title = stringResource(R.string.title_app_icon),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        item {
            RipDpiCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(72.dp)
                                .background(colors.inputBackground, RipDpiThemeTokens.shapes.xxl),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(id = selectedOption.previewRes),
                            contentDescription = stringResource(selectedOption.labelRes),
                            modifier = Modifier.size(72.dp),
                        )
                    }
                    Text(
                        text = stringResource(selectedOption.labelRes),
                        style = type.bodyEmphasis,
                        color = colors.foreground,
                    )
                    Text(
                        text = stringResource(R.string.customization_current_icon_caption),
                        style = type.caption,
                        color = colors.mutedForeground,
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                SettingsCategoryHeader(
                    title = stringResource(R.string.customization_available_icons_section),
                )
                RipDpiCard {
                    IconPickerGrid(
                        options = LauncherIconManager.availableIcons,
                        selectedKey = uiState.appIconVariant,
                        onOptionSelected = onIconSelected,
                    )
                }
            }
        }

        item {
            RipDpiCard {
                Text(
                    text = stringResource(R.string.customization_notice_body),
                    style = type.caption,
                    color = colors.mutedForeground,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                SettingsCategoryHeader(
                    title = stringResource(R.string.customization_options_section),
                )
                RipDpiCard(
                    paddingValues =
                        androidx.compose.foundation.layout.PaddingValues(
                            horizontal = RipDpiThemeTokens.layout.cardPadding,
                            vertical = 0.dp,
                        ),
                ) {
                    SettingsRow(
                        title = stringResource(R.string.customization_shape_title),
                        subtitle = stringResource(R.string.customization_shape_body),
                        value = stringResource(R.string.customization_shape_system_default),
                        onClick = { showAdaptiveShapeSheet = true },
                        showDivider = true,
                        testTag = RipDpiTestTags.CustomizationShapeInfo,
                    )
                    SettingsRow(
                        title = stringResource(R.string.customization_themed_icon_title),
                        subtitle = stringResource(R.string.customization_themed_icon_body),
                        checked = uiState.themedAppIconEnabled,
                        onCheckedChange = onThemedIconChanged,
                        testTag = RipDpiTestTags.CustomizationThemedIcon,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AppCustomizationScreenPreview() {
    RipDpiTheme(themePreference = "light") {
        AppCustomizationScreen(
            uiState =
                SettingsUiState(
                    appIconVariant = LauncherIconManager.DefaultIconKey,
                    themedAppIconEnabled = true,
                ),
            onBack = {},
            onIconSelected = {},
            onThemedIconChanged = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AppCustomizationScreenDarkPreview() {
    RipDpiTheme(themePreference = "dark") {
        AppCustomizationScreen(
            uiState =
                SettingsUiState(
                    appIconVariant = LauncherIconManager.RavenIconKey,
                    themedAppIconEnabled = false,
                ),
            onBack = {},
            onIconSelected = {},
            onThemedIconChanged = {},
        )
    }
}
