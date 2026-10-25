package com.poyka.ripdpi.ui.screens.settings

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
import com.poyka.ripdpi.services.RootAccessResult
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiScreenBackground
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RootModeStrategiesUiState(
    val rootModeEnabled: Boolean = false,
    val rootAccess: RootAccessState = RootAccessState.NotChecked,
)

enum class RootAccessState {
    NotChecked,
    Checking,
    Granted,
    Denied,
    Unavailable,
}

@HiltViewModel
class RootModeStrategiesViewModel
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val rootAccessChecker: RootAccessChecker,
    ) : ViewModel() {
        private val rootAccess = MutableStateFlow(RootAccessState.NotChecked)
        private var rootModeChange: Job? = null

        val uiState: StateFlow<RootModeStrategiesUiState> =
            combine(appSettingsRepository.settings, rootAccess) { settings, access ->
                RootModeStrategiesUiState(rootModeEnabled = settings.rootModeEnabled, rootAccess = access)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = RootModeStrategiesUiState(),
            )

        fun setRootModeEnabled(enabled: Boolean) {
            // A newer toggle replaces the previous one, so a check still running cannot report a stale result.
            rootModeChange?.cancel()
            rootModeChange =
                viewModelScope.launch {
                    appSettingsRepository.update { rootModeEnabled = enabled }
                    if (!enabled) {
                        rootAccess.value = RootAccessState.NotChecked
                        return@launch
                    }
                    // The check runs only after the user turns root mode on, because it can open the root prompt.
                    rootAccess.value = RootAccessState.Checking
                    rootAccess.value =
                        when (rootAccessChecker.check()) {
                            RootAccessResult.Granted -> RootAccessState.Granted
                            RootAccessResult.Denied -> RootAccessState.Denied
                            RootAccessResult.Unavailable -> RootAccessState.Unavailable
                        }
                }
        }

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
        onRootModeEnabledChange = viewModel::setRootModeEnabled,
        modifier = modifier,
    )
}

@Composable
internal fun RootModeStrategiesScreen(
    uiState: RootModeStrategiesUiState,
    onBack: () -> Unit,
    onOpenStrategyConfig: () -> Unit,
    onRootModeEnabledChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.RootModeStrategies))
                .fillMaxWidth()
                .ripDpiScreenBackground(),
        title = stringResource(R.string.title_root_mode_strategies),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        item(key = "root_mode_toggle") {
            RipDpiCard {
                RipDpiSwitch(
                    checked = uiState.rootModeEnabled,
                    onCheckedChange = onRootModeEnabledChange,
                    label = stringResource(R.string.root_mode_toggle_label),
                    helperText = stringResource(R.string.root_mode_strategies_intro_body),
                    testTag = RipDpiTestTags.RootModeToggle,
                )
                RootAccessStatus(uiState.rootAccess)
            }
        }

        // Listed while off too, so the screen shows what root mode enables.
        items(
            items = rootModeStrategies,
            key = { strategy -> "root_mode_strategy_${strategy.testKey}" },
        ) { strategy ->
            RootModeStrategyCard(strategy, enabled = uiState.rootModeEnabled)
        }

        item(key = "root_mode_strategies_configure") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                RipDpiButton(
                    text = stringResource(R.string.root_mode_strategies_configure_action),
                    onClick = onOpenStrategyConfig,
                    enabled = uiState.rootModeEnabled,
                    variant = RipDpiButtonVariant.Outline,
                    trailingIcon = RipDpiIcons.ChevronRight,
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.RootModeStrategiesConfigure),
                )
            }
        }
    }
}

@Composable
private fun RootAccessStatus(state: RootAccessState) {
    val colors = RipDpiThemeTokens.colors
    val (textRes, color) =
        when (state) {
            RootAccessState.NotChecked -> return
            RootAccessState.Checking -> R.string.root_mode_access_checking to colors.mutedForeground
            RootAccessState.Granted -> R.string.root_mode_access_granted to colors.success
            RootAccessState.Denied -> R.string.root_mode_access_denied to colors.warning
            RootAccessState.Unavailable -> R.string.root_mode_access_unavailable to colors.warning
        }
    Text(
        text = stringResource(textRes),
        style = RipDpiThemeTokens.type.secondaryBody,
        color = color,
    )
}

@Composable
private fun RootModeStrategyCard(
    strategy: RootModeStrategyInfo,
    enabled: Boolean,
) {
    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
            Text(
                text = stringResource(strategy.titleRes),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = if (enabled) RipDpiThemeTokens.colors.foreground else RipDpiThemeTokens.colors.mutedForeground,
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
