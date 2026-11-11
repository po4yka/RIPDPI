package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
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
    warpViewModel: WarpEnrollmentViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val warpEnrollment by warpViewModel.state.collectAsStateWithLifecycle()
    val importWarpProfile = rememberWarpProfileImport(warpViewModel)
    val warpProfileName = stringResource(R.string.warp_profile_default_name)
    val hostPackCatalog by viewModel.hostPackCatalog.collectAsStateWithLifecycle()
    val strategyPackCatalog by viewModel.strategyPackCatalog.collectAsStateWithLifecycle()
    val binder = remember(viewModel) { AdvancedSettingsBinder(viewModel::updateSetting) }
    var notice by rememberAdvancedNoticeState()
    val pendingNotice by viewModel.advancedNotice.collectAsStateWithLifecycle()
    val performHaptic = rememberRipDpiHapticPerformer()
    // Notices raised while Advanced was away wait in the ViewModel; show one, then acknowledge it.
    LaunchedEffect(pendingNotice) {
        pendingNotice?.let {
            performHaptic(settingsNoticeHaptic(it))
            notice = mapNoticeEffect(it)
            viewModel.acknowledgeAdvancedNotice(it)
        }
    }
    ObserveSettingsEffects(
        effects = workerViewModel.effects,
        onNotice = { notice = it },
    )

    AdvancedSettingsScreen(
        uiState = uiState,
        warpEnrollment = warpEnrollment,
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
            ).copy(
                onRegisterWarpProfile = { warpViewModel.register(warpProfileName) },
                onImportWarpProfile = importWarpProfile,
                onCancelWarpSetup = warpViewModel::cancel,
            ),
        modifier = modifier,
    )
}
