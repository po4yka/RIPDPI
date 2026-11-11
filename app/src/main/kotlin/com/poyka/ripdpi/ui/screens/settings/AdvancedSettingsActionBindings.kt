package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal fun rememberAdvancedSettingsActions(
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
        onApplyHostPackPreset = remember(viewModel) { viewModel.maintenanceActions::applyHostPackPreset },
        onRefreshHostPackCatalog = remember(viewModel) { viewModel.maintenanceActions::refreshHostPackCatalog },
        onRefreshStrategyPackCatalog = remember(viewModel) { viewModel.maintenanceActions::refreshStrategyPackCatalog },
        onForgetLearnedHosts = remember(viewModel) { viewModel.maintenanceActions::forgetLearnedHosts },
        onClearRememberedNetworks = remember(viewModel) { viewModel.maintenanceActions::clearRememberedNetworks },
        onWsTunnelModeChanged = binder::onWsTunnelModeChanged,
        onSaveWsTunnelWorkerTransport = remember(workerViewModel) { workerViewModel::save },
        onClearWsTunnelWorkerTransport = remember(workerViewModel) { workerViewModel::clear },
        onRotateSalt = remember(viewModel) { viewModel.maintenanceActions::rotateTelemetrySalt },
        onSaveActivationRange = { dimension, start, end ->
            binder.onSaveActivationRange(dimension, start, end, uiState)
        },
        onResetAdaptiveSplit = { binder.onResetAdaptiveSplit(uiState) },
        onResetAdaptiveFakeTtlProfile =
            remember(
                viewModel,
            ) { viewModel.maintenanceActions::resetAdaptiveFakeTtlProfile },
        onResetActivationWindow = remember(viewModel) { viewModel.maintenanceActions::resetActivationWindow },
        onResetHttpParserEvasions = remember(viewModel) { viewModel.maintenanceActions::resetHttpParserEvasions },
        onResetFakePayloadLibrary = remember(viewModel) { viewModel.maintenanceActions::resetFakePayloadLibrary },
        onResetFakeTlsProfile = remember(viewModel) { viewModel.maintenanceActions::resetFakeTlsProfile },
        onRoutingPolicyModeSelected = binder::onRoutingPolicyModeSelected,
        onDhtMitigationModeSelected = binder::onDhtMitigationModeSelected,
        onAntiCorrelationEnabledChanged = binder::onAntiCorrelationEnabledChanged,
        onAppRoutingPresetEnabledChanged = { presetId, enabled ->
            binder.onAppRoutingPresetEnabledChanged(presetId, enabled, uiState)
        },
    )
