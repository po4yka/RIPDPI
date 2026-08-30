package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.activities.SettingsEffect
import com.poyka.ripdpi.activities.SettingsNoticeTone
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer

@Composable
fun AdvancedSettingsRoute(
    onBack: () -> Unit,
    onOpenStrategyConfig: () -> Unit,
    onOpenBlockcheck: () -> Unit,
    onOpenAssetProvider: () -> Unit,
    onOpenRememberedNetworks: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    workerViewModel: WsTunnelWorkerSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hostPackCatalog by viewModel.hostPackCatalog.collectAsStateWithLifecycle()
    val strategyPackCatalog by viewModel.strategyPackCatalog.collectAsStateWithLifecycle()
    val binder = remember(viewModel) { AdvancedSettingsBinder(viewModel::updateSetting) }
    var notice by rememberAdvancedNoticeState()
    ObserveSettingsEffects(
        effects = viewModel.effects,
        onNotice = { notice = it },
    )
    ObserveSettingsEffects(
        effects = workerViewModel.effects,
        onNotice = { notice = it },
    )

    AdvancedSettingsScreen(
        uiState = uiState,
        hostPackCatalog = hostPackCatalog,
        strategyPackCatalog = strategyPackCatalog,
        notice = notice,
        actions =
            rememberAdvancedSettingsActions(
                onBack = onBack,
                onOpenStrategyConfig = onOpenStrategyConfig,
                onOpenBlockcheck = onOpenBlockcheck,
                onOpenAssetProvider = onOpenAssetProvider,
                onOpenRememberedNetworks = onOpenRememberedNetworks,
                viewModel = viewModel,
                workerViewModel = workerViewModel,
                binder = binder,
                uiState = uiState,
            ),
        modifier = modifier,
    )
}

@Composable
private fun rememberAdvancedNoticeState() =
    rememberSaveable(
        stateSaver =
            mapSaver(
                save = { notice ->
                    if (notice != null) {
                        mapOf("t" to notice.title, "m" to notice.message, "tone" to notice.tone.name)
                    } else {
                        emptyMap()
                    }
                },
                restore = { values ->
                    if (values.isNotEmpty()) {
                        AdvancedNotice(
                            title = values["t"] as String,
                            message = values["m"] as String,
                            tone = WarningBannerTone.valueOf(values["tone"] as String),
                        )
                    } else {
                        null
                    }
                },
            ),
    ) { mutableStateOf<AdvancedNotice?>(null) }

@Composable
private fun ObserveSettingsEffects(
    effects: kotlinx.coroutines.flow.Flow<SettingsEffect>,
    onNotice: (AdvancedNotice) -> Unit,
) {
    val performHaptic = rememberRipDpiHapticPerformer()

    LifecycleEventEffect(effects) { effect ->
        if (effect is SettingsEffect.Notice) {
            performHaptic(settingsNoticeHaptic(effect))
            onNotice(mapNoticeEffect(effect))
        }
    }
}

private fun settingsNoticeHaptic(effect: SettingsEffect.Notice): RipDpiHapticFeedback =
    when (effect.tone) {
        SettingsNoticeTone.Error -> RipDpiHapticFeedback.Error
        SettingsNoticeTone.Info, SettingsNoticeTone.Warning -> RipDpiHapticFeedback.Acknowledge
    }

@Composable
private fun rememberAdvancedSettingsActions(
    onBack: () -> Unit,
    onOpenStrategyConfig: () -> Unit,
    onOpenBlockcheck: () -> Unit,
    onOpenAssetProvider: () -> Unit,
    onOpenRememberedNetworks: () -> Unit,
    viewModel: SettingsViewModel,
    workerViewModel: WsTunnelWorkerSettingsViewModel,
    binder: AdvancedSettingsBinder,
    uiState: com.poyka.ripdpi.ui.state.SettingsUiState,
): AdvancedSettingsActions =
    AdvancedSettingsActions(
        onBack = onBack,
        onOpenStrategyConfig = onOpenStrategyConfig,
        onOpenBlockcheck = onOpenBlockcheck,
        onOpenAssetProvider = onOpenAssetProvider,
        onOpenRememberedNetworks = onOpenRememberedNetworks,
        onToggleChanged = binder::onToggleChanged,
        onTextConfirmed = { setting, value -> binder.onTextConfirmed(setting, value, uiState) },
        onOptionSelected = { setting, value -> binder.onOptionSelected(setting, value, uiState) },
        onApplyHostPackPreset = remember(viewModel) { viewModel::applyHostPackPreset },
        onRefreshHostPackCatalog = remember(viewModel) { viewModel::refreshHostPackCatalog },
        onRefreshStrategyPackCatalog = remember(viewModel) { viewModel::refreshStrategyPackCatalog },
        onForgetLearnedHosts = remember(viewModel) { viewModel::forgetLearnedHosts },
        onClearRememberedNetworks = remember(viewModel) { viewModel::clearRememberedNetworks },
        onWsTunnelModeChanged = binder::onWsTunnelModeChanged,
        onSaveWsTunnelWorkerTransport = remember(workerViewModel) { workerViewModel::save },
        onClearWsTunnelWorkerTransport = remember(workerViewModel) { workerViewModel::clear },
        onRotateSalt = remember(viewModel) { viewModel::rotateTelemetrySalt },
        onSaveActivationRange = { dimension, start, end ->
            binder.onSaveActivationRange(dimension, start, end, uiState)
        },
        onResetAdaptiveSplit = { binder.onResetAdaptiveSplit(uiState) },
        onResetAdaptiveFakeTtlProfile = remember(viewModel) { viewModel::resetAdaptiveFakeTtlProfile },
        onResetActivationWindow = remember(viewModel) { viewModel::resetActivationWindow },
        onResetHttpParserEvasions = remember(viewModel) { viewModel::resetHttpParserEvasions },
        onResetFakePayloadLibrary = remember(viewModel) { viewModel::resetFakePayloadLibrary },
        onResetFakeTlsProfile = remember(viewModel) { viewModel::resetFakeTlsProfile },
        onRoutingPolicyModeSelected = binder::onRoutingPolicyModeSelected,
        onDhtMitigationModeSelected = binder::onDhtMitigationModeSelected,
        onAntiCorrelationEnabledChanged = binder::onAntiCorrelationEnabledChanged,
        onAppRoutingPresetEnabledChanged = { presetId, enabled ->
            binder.onAppRoutingPresetEnabledChanged(presetId, enabled, uiState)
        },
    )
