package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R

/**
 * Default-state entry point for [OomRecoveryScreen]. Production
 * callers should pass a real [OomRecoveryState] derived from live
 * VPN engine events; this default exists so the developer-reachable nav
 * entry demonstrates the wiring end-to-end.
 */
@Composable
fun OomRecoveryRoute(onBack: () -> Unit = {}) {
    var isDismissed by rememberSaveable { mutableStateOf(false) }
    val state =
        sampleOomRecoveryState(
            killTimeLabel = stringResource(R.string.oom_recovery_sample_kill_time),
            downtimeLabel = stringResource(R.string.oom_recovery_sample_downtime),
        ).copy(isDismissed = isDismissed)
    OomRecoveryScreen(
        state = state,
        onReconnect = {},
        onViewIncident = {},
        onDismiss = { isDismissed = true },
        onBack = onBack,
    )
}
