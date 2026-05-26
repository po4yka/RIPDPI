package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
fun FirstRunTestRoute(
    onSkip: () -> Unit,
    onApplyRecommendation: () -> Unit,
) {
    LaunchedEffect(onSkip) {
        onSkip()
    }
}
