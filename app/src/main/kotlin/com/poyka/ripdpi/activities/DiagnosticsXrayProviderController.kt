package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
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
    private val serviceStateStore: ServiceStateStore,
    private val probeCoordinator: XrayProviderProbeCoordinator,
) {
    private val _probeReport = MutableStateFlow<XrayProviderProbeReport?>(null)
    private var probeKey: ProbeSessionKey? = null

    /** Live provider snapshot from the telemetry loop; null on the native path. */
    val snapshot: StateFlow<XrayProviderSnapshot?> =
        serviceStateStore.telemetry
            .map {
                if (probeKey != null && probeKey != it.probeSessionKey()) {
                    _probeReport.value = null
                    probeKey = null
                }
                it.xrayProviderSnapshot
            }.stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = serviceStateStore.telemetry.value.xrayProviderSnapshot,
            )

    /**
     * Latest user-triggered probe report, or null if never run / no active
     * provider. A session or health change invalidates the report.
     */
    val probeReport: StateFlow<XrayProviderProbeReport?> = _probeReport.asStateFlow()

    private val _probeRunning = MutableStateFlow(false)
    val probeRunning: StateFlow<Boolean> = _probeRunning.asStateFlow()

    /** Run the provider-path probes against the active provider (user-triggered). */
    fun runProbe() {
        if (_probeRunning.value) return
        val initial = serviceStateStore.telemetry.value.probeSessionKey()
        if (initial.status != AppStatus.Running || initial.snapshot == null) return
        _probeRunning.value = true
        scope.launch {
            try {
                // Probe collaborators are synchronous in-process reads (version /
                // listenerReady / isAlive); the coordinator returns null when no
                // Xray session is bound.
                val report = probeCoordinator.runProbes()
                if (serviceStateStore.telemetry.value.probeSessionKey() == initial) {
                    probeKey = initial
                    _probeReport.value = report
                }
            } finally {
                _probeRunning.value = false
            }
        }
    }

    private data class ProbeSessionKey(
        val startedAt: Long?,
        val restartCount: Int,
        val status: AppStatus,
        val mode: Mode?,
        val snapshot: XrayProviderSnapshot?,
    )

    private fun ServiceTelemetrySnapshot.probeSessionKey() =
        ProbeSessionKey(
            serviceStartedAt,
            restartCount,
            status,
            mode,
            xrayProviderSnapshot?.copy(capturedAt = 0),
        )
}
