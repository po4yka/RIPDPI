package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.poyka.ripdpi.activities.HostPackCatalogUiState
import com.poyka.ripdpi.activities.StrategyPackCatalogUiState
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.state.SettingsUiState

@Composable
internal fun AdvancedSettingsScreen(
    uiState: SettingsUiState,
    hostPackCatalog: HostPackCatalogUiState,
    strategyPackCatalog: StrategyPackCatalogUiState,
    notice: AdvancedNotice?,
    actions: AdvancedSettingsActions,
    modifier: Modifier = Modifier,
) {
    TrackRecomposition("AdvancedSettingsScreen")
    val contentState = rememberAdvancedSettingsContentState(uiState)
    var pendingHostPackId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedHostPackTargetMode by rememberSaveable { mutableStateOf(defaultHostPackTargetMode(uiState)) }
    var selectedHostPackApplyMode by rememberSaveable { mutableStateOf(HostPackApplyDialogDefaultMode) }

    val pendingHostPack = pendingHostPackId?.let { id -> hostPackCatalog.presets.find { it.id == id } }

    pendingHostPack?.let { preset ->
        HostPackApplyDialog(
            preset = preset,
            targetMode = selectedHostPackTargetMode,
            applyMode = selectedHostPackApplyMode,
            onTargetModeChanged = { selectedHostPackTargetMode = it },
            onApplyModeChanged = { selectedHostPackApplyMode = it },
            onDismiss = { pendingHostPackId = null },
            onApply = {
                actions.onApplyHostPackPreset(
                    preset,
                    selectedHostPackTargetMode,
                    selectedHostPackApplyMode,
                )
                pendingHostPackId = null
            },
        )
    }

    AdvancedSettingsContent(
        uiState = uiState,
        hostPackCatalog = hostPackCatalog,
        strategyPackCatalog = strategyPackCatalog,
        notice = notice,
        actions = actions,
        contentState = contentState,
        pendingHostPack = pendingHostPack,
        onPresetSelected = { preset ->
            selectedHostPackTargetMode = defaultHostPackTargetMode(uiState)
            selectedHostPackApplyMode = HostPackApplyDialogDefaultMode
            pendingHostPackId = preset.id
        },
        modifier = modifier,
    )
}
