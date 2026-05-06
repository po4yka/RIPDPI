package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HostPackCatalogUiState
import com.poyka.ripdpi.activities.StrategyPackCatalogUiState
import com.poyka.ripdpi.data.HostPackPreset
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun AdvancedSettingsContent(
    uiState: SettingsUiState,
    hostPackCatalog: HostPackCatalogUiState,
    strategyPackCatalog: StrategyPackCatalogUiState,
    notice: AdvancedNotice?,
    actions: AdvancedSettingsActions,
    contentState: AdvancedSettingsContentState,
    pendingHostPack: HostPackPreset?,
    onPresetSelected: (HostPackPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.AdvancedSettings))
                .fillMaxSize()
                .background(colors.background),
        title = stringResource(R.string.title_advanced_settings),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = actions.onBack,
    ) {
        advancedSettingsBannerItems(uiState, notice)
        advancedSettingsPrimarySections(uiState, actions, contentState)
        advancedSettingsSecondarySections(
            uiState = uiState,
            hostPackCatalog = hostPackCatalog,
            strategyPackCatalog = strategyPackCatalog,
            actions = actions,
            contentState = contentState,
            pendingHostPack = pendingHostPack,
            onPresetSelected = onPresetSelected,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.advancedSettingsBannerItems(
    uiState: SettingsUiState,
    notice: AdvancedNotice?,
) {
    if (uiState.enableCmdSettings) {
        item(key = "advanced_settings_warning") {
            WarningBanner(
                title = stringResource(R.string.config_cli_banner_title),
                message = stringResource(R.string.config_cli_banner_body),
                tone = WarningBannerTone.Restricted,
                testTag = RipDpiTestTags.AdvancedCommandLineWarning,
            )
        }
    }
    notice?.let {
        item(key = "advanced_settings_notice") {
            WarningBanner(
                title = it.title,
                message = it.message,
                tone = it.tone,
                testTag = RipDpiTestTags.AdvancedNoticeBanner,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.advancedSettingsSecondarySections(
    uiState: SettingsUiState,
    hostPackCatalog: HostPackCatalogUiState,
    strategyPackCatalog: StrategyPackCatalogUiState,
    actions: AdvancedSettingsActions,
    contentState: AdvancedSettingsContentState,
    pendingHostPack: HostPackPreset?,
    onPresetSelected: (HostPackPreset) -> Unit,
) {
    advancedSettingsProtectionSections(uiState, actions, contentState)
    advancedSettingsProtocolSections(
        uiState = uiState,
        hostPackCatalog = hostPackCatalog,
        strategyPackCatalog = strategyPackCatalog,
        actions = actions,
        contentState = contentState,
        pendingHostPack = pendingHostPack,
        onPresetSelected = onPresetSelected,
    )
}
