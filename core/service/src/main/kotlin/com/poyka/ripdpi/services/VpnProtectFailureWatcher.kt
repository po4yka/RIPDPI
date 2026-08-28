package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ServiceStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class VpnProtectFailureWatcher(
    private val dependencies: VpnTelemetryRuntimeDependencies,
    private val state: VpnTelemetryStateAccess,
    private val callbacks: VpnTelemetryFailureCallbacks,
) {
    private var job: Job? = null

    fun start() {
        stop()
        job =
            dependencies.host.serviceScope.launch(dependencies.ioDispatcher) {
                dependencies.vpnProtectFailureMonitor.events.collect { event ->
                    handle(event)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun handle(event: VpnProtectFailureEvent) {
        if (state.status() != ServiceStatus.Connected || state.stopping()) {
            return
        }
        val boundary =
            VpnTelemetryFailureBoundary(
                session = state.runtimeSession(),
                xrayGeneration = event.providerGeneration ?: dependencies.xrayController?.currentGenerationIfActive(),
            )
        Logger.e { "VPN protect failed for fd=${event.fd}: ${event.detail}" }
        val guard =
            boundary.providerStopGuard(
                controller = dependencies.xrayController,
                failureReason = event.reason,
                currentSession = state::runtimeSession,
            )
        callbacks.failAndStopService(event.reason, guard)
    }
}
