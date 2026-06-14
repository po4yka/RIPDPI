package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class RootModeStrategiesUiState(
    val rootModeEnabled: Boolean = false,
)

@HiltViewModel
class RootModeStrategiesViewModel
    @Inject
    constructor(
        appSettingsRepository: AppSettingsRepository,
    ) : ViewModel() {
        val uiState: StateFlow<RootModeStrategiesUiState> =
            appSettingsRepository.settings
                .map { RootModeStrategiesUiState(rootModeEnabled = it.rootModeEnabled) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    initialValue = RootModeStrategiesUiState(),
                )

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

private data class RootModeStrategyInfo(
    val titleRes: Int,
    val bodyRes: Int,
    val testKey: String,
)

private val rootModeStrategies =
    listOf(
        RootModeStrategyInfo(
            R.string.root_mode_strategy_fakerst_title,
            R.string.root_mode_strategy_fakerst_body,
            "fakerst",
        ),
        RootModeStrategyInfo(
            R.string.root_mode_strategy_multidisorder_title,
            R.string.root_mode_strategy_multidisorder_body,
            "multidisorder",
        ),
        RootModeStrategyInfo(
            R.string.root_mode_strategy_ipfrag2_title,
            R.string.root_mode_strategy_ipfrag2_body,
            "ipfrag2",
        ),
    )

@Composable
fun RootModeStrategiesRoute(
    onBack: () -> Unit,
    onOpenStrategyConfig: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RootModeStrategiesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    RootModeStrategiesScreen(
        uiState = uiState,
        onBack = onBack,
        onOpenStrategyConfig = onOpenStrategyConfig,
        modifier = modifier,
    )
}

@Composable
internal fun RootModeStrategiesScreen(
    uiState: RootModeStrategiesUiState,
    onBack: () -> Unit,
    onOpenStrategyConfig: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.RootModeStrategies))
                .fillMaxWidth()
                .background(colors.background),
        title = stringResource(R.string.title_root_mode_strategies),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        item(key = "root_mode_strategies_intro") {
            WarningBanner(
                title = stringResource(R.string.root_mode_strategies_intro_title),
                message = stringResource(R.string.root_mode_strategies_intro_body),
                tone = WarningBannerTone.Info,
            )
        }

        if (!uiState.rootModeEnabled) {
            item(key = "root_mode_strategies_disabled") {
                RipDpiCard(modifier = Modifier.ripDpiTestTag(RipDpiTestTags.RootModeStrategiesDisabled)) {
                    Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
                        Text(
                            text = stringResource(R.string.root_mode_strategies_disabled_title),
                            style = RipDpiThemeTokens.type.bodyEmphasis,
                            color = colors.foreground,
                        )
                        Text(
                            text = stringResource(R.string.root_mode_strategies_disabled_body),
                            style = RipDpiThemeTokens.type.caption,
                            color = colors.mutedForeground,
                        )
                    }
                }
            }
            return@RipDpiSettingsScaffold
        }

        items(
            items = rootModeStrategies,
            key = { strategy -> "root_mode_strategy_${strategy.testKey}" },
        ) { strategy ->
            RootModeStrategyCard(strategy)
        }

        item(key = "root_mode_strategies_configure") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                RipDpiButton(
                    text = stringResource(R.string.root_mode_strategies_configure_action),
                    onClick = onOpenStrategyConfig,
                    variant = RipDpiButtonVariant.Outline,
                    trailingIcon = RipDpiIcons.ChevronRight,
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.RootModeStrategiesConfigure),
                )
            }
        }
    }
}

@Composable
private fun RootModeStrategyCard(strategy: RootModeStrategyInfo) {
    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
            Text(
                text = stringResource(strategy.titleRes),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
            )
            Text(
                text = stringResource(strategy.bodyRes),
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun previewRootModeStrategiesEnabled() {
    RipDpiTheme(themePreference = "light") {
        RootModeStrategiesScreen(
            uiState = RootModeStrategiesUiState(rootModeEnabled = true),
            onBack = {},
            onOpenStrategyConfig = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun previewRootModeStrategiesDisabled() {
    RipDpiTheme(themePreference = "light") {
        RootModeStrategiesScreen(
            uiState = RootModeStrategiesUiState(rootModeEnabled = false),
            onBack = {},
            onOpenStrategyConfig = {},
        )
    }
}
