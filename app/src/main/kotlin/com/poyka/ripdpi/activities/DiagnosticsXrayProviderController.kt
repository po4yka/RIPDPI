package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.xray.XrayProviderProbeCoordinator
import com.poyka.ripdpi.data.xray.XrayProviderProbeReport
import com.poyka.ripdpi.data.xray.XrayProviderSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * User-triggered embedded-Xray provider-path probe controller for Diagnostics.
 *
 * Mirrors the [DiagnosticsDpiToolsController] `run*()` pattern (decision 5/6):
 * the provider-path probe runs ONLY when the user taps it and ONLY against the
 * ACTIVE running provider — it performs no auto-scanning. The live provider
 * snapshot is observed from [ServiceStateStore.telemetry] (the same telemetry
 * surface the rest of the app reads); the probe itself is dispatched through the
 * process-wide [XrayProviderProbeCoordinator], which returns null when no Xray
 * session is bound. A null report renders as "no active provider".
 *
 * The produced [XrayProviderProbeReport] is already privacy-safe (the deriver and
 * `XrayProfileRedactor` guarantee no secret/endpoint reaches it).
 */
internal class DiagnosticsXrayProviderController(
    private val scope: CoroutineScope,
    serviceStateStore: ServiceStateStore,
    private val probeCoordinator: XrayProviderProbeCoordinator,
) {
    /** Live provider snapshot from the telemetry loop; null on the native path. */
    val snapshot: StateFlow<XrayProviderSnapshot?> =
        serviceStateStore.telemetry
            .map { it.xrayProviderSnapshot }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = serviceStateStore.telemetry.value.xrayProviderSnapshot,
            )

    private val _probeReport = MutableStateFlow<XrayProviderProbeReport?>(null)

    /**
     * Latest user-triggered probe report, or null if never run / no active
     * provider. When present it supersedes the passive snapshot for display.
     */
    val probeReport: StateFlow<XrayProviderProbeReport?> = _probeReport.asStateFlow()

    private val _probeRunning = MutableStateFlow(false)
    val probeRunning: StateFlow<Boolean> = _probeRunning.asStateFlow()

    /** Run the provider-path probes against the active provider (user-triggered). */
    fun runProbe() {
        if (_probeRunning.value) return
        _probeRunning.value = true
        scope.launch {
            try {
                // Probe collaborators are synchronous in-process reads (version /
                // listenerReady / isAlive); the coordinator returns null when no
                // Xray session is bound.
                _probeReport.value = probeCoordinator.runProbes()
            } finally {
                _probeRunning.value = false
            }
        }
    }
}
