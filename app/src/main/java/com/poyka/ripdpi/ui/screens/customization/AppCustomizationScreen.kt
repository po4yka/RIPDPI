package com.poyka.ripdpi.ui.screens.customization

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.LauncherIconManager
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
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
    val layout = RipDpiThemeTokens.layout
    val type = RipDpiThemeTokens.type
    val selectedOption = LauncherIconManager.resolveOption(uiState.appIconVariant)
    var showAdaptiveShapeSheet by rememberSaveable { mutableStateOf(false) }

    if (showAdaptiveShapeSheet) {
        RipDpiBottomSheet(
            onDismissRequest = { showAdaptiveShapeSheet = false },
            title = stringResource(R.string.customization_shape_sheet_title),
            message = stringResource(R.string.customization_shape_sheet_body),
            icon = RipDpiIcons.Info,
            primaryActionLabel = stringResource(R.string.customization_shape_sheet_action),
            onPrimaryAction = { showAdaptiveShapeSheet = false },
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

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background),
    ) {
        RipDpiTopAppBar(
            title = stringResource(R.string.title_app_icon),
            navigationIcon = RipDpiIcons.Back,
            onNavigationClick = onBack,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = layout.horizontalPadding,
                    top = spacing.sm,
                    end = layout.horizontalPadding,
                    bottom = spacing.xxl,
                ),
            verticalArrangement = Arrangement.spacedBy(layout.sectionGap),
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
                    RipDpiCard(paddingValues = PaddingValues(horizontal = layout.cardPadding, vertical = 0.dp)) {
                        SettingsRow(
                            title = stringResource(R.string.customization_shape_title),
                            subtitle = stringResource(R.string.customization_shape_body),
                            value = stringResource(R.string.customization_shape_system_default),
                            onClick = { showAdaptiveShapeSheet = true },
                            showDivider = true,
                        )
                        SettingsRow(
                            title = stringResource(R.string.customization_themed_icon_title),
                            subtitle = stringResource(R.string.customization_themed_icon_body),
                            checked = uiState.themedAppIconEnabled,
                            onCheckedChange = onThemedIconChanged,
                        )
                    }
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
