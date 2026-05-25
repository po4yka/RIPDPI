package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable

/**
 * Default-state entry point for [StrategyImportScreen]. Production callers
 * should pass a real [StrategyImportState] derived from the import engine;
 * this default exists so the developer-reachable nav entry demonstrates the
 * wiring end-to-end.
 */
@Composable
fun StrategyImportRoute(onBack: () -> Unit = {}) {
    StrategyImportScreen(
        state = sampleStrategyImportState(),
        onBack = onBack,
    )
}
