package com.poyka.ripdpi.activities

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Matches the experience's existing authentication gate and keeps background handoffs pending. */
@Composable
internal fun ExportPreviewContentGate(
    unlocked: Boolean,
    onEligibilityChanged: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow
        .collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val eligible = unlocked && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    DisposableEffect(onEligibilityChanged) {
        onDispose { onEligibilityChanged(false) }
    }
    SideEffect { onEligibilityChanged(eligible) }
    if (eligible) content()
}
