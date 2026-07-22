package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
internal fun StrategyConfigHydrationEffect(
    editorViewModel: StrategyConfigEditorViewModel,
    configText: String,
) {
    LaunchedEffect(configText) {
        editorViewModel.syncBuiltIn(configText)
    }
}

@Composable
internal fun StrategyConfigExitEffect(
    decision: StrategyConfigExitDecision?,
    onConsumed: (StrategyConfigExitDecision) -> Unit,
    onDirty: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(decision) {
        decision ?: return@LaunchedEffect
        onConsumed(decision)
        when (decision) {
            StrategyConfigExitDecision.ConfirmDiscard -> onDirty()
            StrategyConfigExitDecision.NavigateBack -> onBack()
        }
    }
}
