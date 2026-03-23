package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.activities.SettingsEffect
import com.poyka.ripdpi.activities.SettingsViewModel
import kotlinx.coroutines.flow.collect

@Composable
fun AdvancedSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hostPackCatalog by viewModel.hostPackCatalog.collectAsStateWithLifecycle()
    val binder = remember(viewModel) { AdvancedSettingsBinder(viewModel::updateSetting) }
    var notice by remember { mutableStateOf<AdvancedNotice?>(null) }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            if (effect is SettingsEffect.Notice) {
                notice = mapNoticeEffect(effect)
            }
        }
    }

    AdvancedSettingsScreen(
        uiState = uiState,
        hostPackCatalog = hostPackCatalog,
        notice = notice,
        actions =
            AdvancedSettingsActions(
                onBack = onBack,
                onToggleChanged = binder::onToggleChanged,
                onTextConfirmed = { setting, value ->
                    binder.onTextConfirmed(
                        setting = setting,
                        value = value,
                        uiState = uiState,
                    )
                },
                onOptionSelected = { setting, value ->
                    binder.onOptionSelected(
                        setting = setting,
                        value = value,
                        uiState = uiState,
                    )
                },
                onApplyHostPackPreset = viewModel::applyHostPackPreset,
                onRefreshHostPackCatalog = viewModel::refreshHostPackCatalog,
                onForgetLearnedHosts = viewModel::forgetLearnedHosts,
                onClearRememberedNetworks = viewModel::clearRememberedNetworks,
                onWsTunnelModeChanged = binder::onWsTunnelModeChanged,
                onRotateTelemetrySalt = viewModel::rotateTelemetrySalt,
                onSaveActivationRange = { dimension, start, end ->
                    binder.onSaveActivationRange(
                        dimension = dimension,
                        start = start,
                        end = end,
                        uiState = uiState,
                    )
                },
                onResetAdaptiveSplit = { binder.onResetAdaptiveSplit(uiState) },
                onResetAdaptiveFakeTtlProfile = viewModel::resetAdaptiveFakeTtlProfile,
                onResetActivationWindow = viewModel::resetActivationWindow,
                onResetHttpParserEvasions = viewModel::resetHttpParserEvasions,
                onResetFakePayloadLibrary = viewModel::resetFakePayloadLibrary,
                onResetFakeTlsProfile = viewModel::resetFakeTlsProfile,
            ),
        modifier = modifier,
    )
}
