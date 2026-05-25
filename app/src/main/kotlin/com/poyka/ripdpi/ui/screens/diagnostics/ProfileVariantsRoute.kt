package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable

/**
 * Default-state entry point for [ProfileVariantsScreen]. Production callers
 * should pass a real [ProfileVariantsState] derived from the profile engine;
 * this default exists so the developer-reachable nav entry demonstrates the
 * wiring end-to-end.
 */
@Composable
fun ProfileVariantsRoute(onBack: () -> Unit = {}) {
    ProfileVariantsScreen(
        state = sampleProfileVariantsState(),
        onBack = onBack,
    )
}
