package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.poyka.ripdpi.services.DefaultServiceRuntimeRegistry
import com.poyka.ripdpi.services.RootAccessResult
import com.poyka.ripdpi.services.RootHelperManager
import com.poyka.ripdpi.services.RootHelperState
import com.poyka.ripdpi.services.RuntimePolicyReloadIntent
import com.poyka.ripdpi.services.ServiceRuntimeRegistry
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.RootModeStrategies
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiScreenBackground
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal object RootModeTestTags {
    const val SettingsEntry = "settings-root-mode-strategies"
    const val Configure = "root-mode-strategies-configure"
    const val Retry = "root-mode-retry"
    const val Toggle = "root-mode-toggle"
}

data class RootModeStrategiesUiState(
    val rootModeEnabled: Boolean = false,
    val rootAccess: RootAccessState = RootAccessState.NotChecked,
    val helper: RootHelperState = RootHelperState.Stopped,
)

enum class RootAccessState {
    NotChecked,
    Checking,
    Granted,
    Denied,
    Unavailable,
    TimedOut,
}

@HiltViewModel
class RootModeStrategiesViewModel
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val rootHelperManager: RootHelperManager,
        private val runtimeRegistry: ServiceRuntimeRegistry,
        private val rootAccessChecker: RootAccessChecker,
    ) : ViewModel() {
        internal constructor(
            appSettingsRepository: AppSettingsRepository,
            rootAccessChecker: RootAccessChecker,
        ) : this(appSettingsRepository, RootHelperManager(), DefaultServiceRuntimeRegistry(), rootAccessChecker)

        private val checkingAccess = MutableStateFlow(false)
        private var rootModeChange: Job? = null

        val uiState: StateFlow<RootModeStrategiesUiState> =
            combine(
                appSettingsRepository.settings,
                checkingAccess,
                rootHelperManager.lastAccessResult,
                rootHelperManager.state,
            ) { settings, checking, access, helper ->
                RootModeStrategiesUiState(
                    settings.rootModeAuthorized,
                    if (checking) RootAccessState.Checking else access.toUiState(),
                    helper,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = RootModeStrategiesUiState(),
            )

        fun setRootModeEnabled(enabled: Boolean) {
            // A newer toggle replaces the previous one, so a check still running cannot report a stale result.
            rootModeChange?.cancel()
            rootModeChange =
                // A check that throws (the su process cannot start) must not leave the spinner on.
                launchSettingsWrite(onFailure = {
                    if (checkingAccess.value) {
                        rootHelperManager.recordAccessResult(RootAccessResult.Unavailable)
                        checkingAccess.value = false
                    }
                }) {
                    if (!enabled) {
                        appSettingsRepository.update { rootModeAuthorized = false }
                        rootHelperManager.recordAccessResult(null)
                        checkingAccess.value = false
                        return@launchSettingsWrite
                    }
                    appSettingsRepository.update {}
                    checkingAccess.value = true
                    val access = rootAccessChecker.check()
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        val authorized = access == RootAccessResult.Granted
                        appSettingsRepository.update { rootModeAuthorized = authorized }
                        rootHelperManager.recordAccessResult(access)
                        checkingAccess.value = false
                        if (authorized) {
                            rootHelperManager.allowRetry()
                            reloadRootPolicy()
                        }
                    }
                }
        }

        private suspend fun reloadRootPolicy() {
            for ((mode, runtime) in runtimeRegistry.runtimes.value) {
                runtime.reloadConnectionPolicy(RuntimePolicyReloadIntent.Automatic) {
                    runtimeRegistry.current(mode)?.runtimeId == runtime.runtimeId &&
                        appSettingsRepository.snapshot().rootModeAuthorized
                }
            }
        }

        fun retryRootAccess() = setRootModeEnabled(true)

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

private fun RootAccessResult?.toUiState(): RootAccessState =
    when (this) {
        RootAccessResult.Granted -> RootAccessState.Granted
        RootAccessResult.Denied -> RootAccessState.Denied
        RootAccessResult.Unavailable -> RootAccessState.Unavailable
        RootAccessResult.TimedOut -> RootAccessState.TimedOut
        null -> RootAccessState.NotChecked
    }

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
        onRetry = viewModel::retryRootAccess,
        modifier = modifier,
    )
}

@Composable
internal fun RootModeStrategiesScreen(
    uiState: RootModeStrategiesUiState,
    onBack: () -> Unit,
    onOpenStrategyConfig: () -> Unit,
    onRootModeEnabledChange: (Boolean) -> Unit = {},
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(RootModeStrategies))
                .fillMaxWidth()
                .ripDpiScreenBackground(),
        title = stringResource(R.string.title_root_mode_strategies),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        item(key = "root_mode_toggle") {
            RipDpiCard {
                RipDpiSwitch(
                    checked = uiState.rootModeEnabled || uiState.rootAccess == RootAccessState.Checking,
                    onCheckedChange = onRootModeEnabledChange,
                    label = stringResource(R.string.root_mode_toggle_label),
                    helperText = stringResource(R.string.root_mode_strategies_intro_body),
                    testTag = RootModeTestTags.Toggle,
                )
                RootAccessStatus(uiState)
                if (uiState.canRetry) {
                    RipDpiButton(
                        text = stringResource(R.string.root_mode_access_retry),
                        onClick = onRetry,
                        variant = RipDpiButtonVariant.Outline,
                        modifier = Modifier.ripDpiTestTag(RootModeTestTags.Retry),
                    )
                }
            }
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
                    modifier = Modifier.ripDpiTestTag(RootModeTestTags.Configure),
                )
            }
        }
    }
}

private val RootModeStrategiesUiState.canRetry: Boolean
    get() =
        when (rootAccess) {
            RootAccessState.Checking -> false
            RootAccessState.Denied, RootAccessState.Unavailable, RootAccessState.TimedOut -> true
            else -> rootModeEnabled && helper.unavailable
        }

@Composable
private fun RootAccessStatus(state: RootModeStrategiesUiState) {
    val colors = RipDpiThemeTokens.colors
    val (textRes, color) =
        when {
            state.rootAccess == RootAccessState.Checking -> {
                R.string.root_mode_access_checking to colors.mutedForeground
            }

            state.rootAccess == RootAccessState.Denied -> {
                R.string.root_mode_access_denied to colors.warning
            }

            state.rootAccess == RootAccessState.Unavailable -> {
                R.string.root_mode_access_unavailable to colors.warning
            }

            state.rootAccess == RootAccessState.TimedOut -> {
                R.string.root_mode_access_timed_out to colors.warning
            }

            state.helper == RootHelperState.Ready -> {
                R.string.root_mode_helper_active to colors.success
            }

            state.helper == RootHelperState.Starting -> {
                R.string.root_mode_helper_starting to colors.mutedForeground
            }

            state.rootModeEnabled && state.helper == RootHelperState.OrdinaryConnection -> {
                R.string.root_mode_helper_ordinary_connection to colors.warning
            }

            state.rootModeEnabled && state.helper == RootHelperState.Failed -> {
                R.string.root_mode_helper_failed to colors.warning
            }

            state.rootModeEnabled -> {
                R.string.root_mode_access_granted to colors.mutedForeground
            }

            else -> {
                return
            }
        }
    Text(
        text = stringResource(textRes),
        style = RipDpiThemeTokens.type.secondaryBody,
        color = color,
    )
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
