package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable

/**
 * Default-state entry point for [StrategyAbScreen]. Production callers
 * should pass a real [StrategyAbState] derived from live A/B test engine
 * events; this default exists so the developer-reachable nav entry
 * demonstrates the wiring end-to-end.
 */
@Composable
fun StrategyAbRoute(onBack: () -> Unit = {}) {
    StrategyAbScreen(
        state = sampleStrategyAbState(),
        onSwitch = {},
        onBack = onBack,
    )
}
