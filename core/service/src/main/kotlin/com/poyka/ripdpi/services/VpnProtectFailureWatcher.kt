package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.ServiceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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
            dependencies.host.serviceScope.launch(dependencies.ioDispatcher, start = CoroutineStart.UNDISPATCHED) {
                dependencies.vpnProtectFailureMonitor.events.collect { event ->
                    try {
                        handle(event)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (
                        @Suppress("TooGenericExceptionCaught") error: Exception,
                    ) {
                        // An escaped failure would kill the process with the TUN up and stop watching.
                        Logger.e(error, tag = LogTags.SERVICE) { "VPN protect failure handling failed" }
                    }
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
        if (!dependencies.host.isVpnConsentRevoked()) {
            // The native side already failed this one socket. With the consent still held,
            // one rejected or malformed request is no reason to take the whole tunnel down.
            Logger.w(tag = LogTags.SERVICE) { "VPN protect failed for one socket, fd=${event.fd}: ${event.detail}" }
            return
        }
        val boundary =
            VpnTelemetryFailureBoundary(
                session = state.runtimeSession(),
                xrayGeneration = event.providerGeneration ?: dependencies.xrayController?.currentGenerationIfActive(),
            )
        Logger.e(tag = LogTags.SERVICE) { "VPN protect failed for fd=${event.fd}: ${event.detail}" }
        val guard =
            boundary.providerStopGuard(
                controller = dependencies.xrayController,
                failureReason = event.reason,
                currentSession = state::runtimeSession,
            )
        callbacks.failAndStopService(event.reason, guard)
    }
}
