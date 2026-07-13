package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.classifyFailureReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch

internal class VpnSupervisorExitHandler(
    private val host: VpnCoordinatorHost,
    private val ioDispatcher: CoroutineDispatcher,
    private val proxyRuntimeStack: SharedProxyRuntimeStack,
    private val upstreamRelaySupervisor: UpstreamRelaySupervisor,
    private val warpRuntimeSupervisor: WarpRuntimeSupervisor,
    private val amneziaWgRuntimeSupervisor: AmneziaWgRuntimeSupervisor,
    private val updateStatus: (ServiceStatus, FailureReason?) -> Unit,
    private val markForeignRelayFailed: () -> Unit,
    private val stopService: suspend (skipRuntimeShutdown: Boolean) -> Unit,
) {
    suspend fun handleProxyExit(cause: SupervisorExitCause) {
        if (cause is SupervisorExitCause.ExpectedStop) {
            return
        }
        if (cause is SupervisorExitCause.Crash && cause.code == 0) {
            Logger.i { "VPN local proxy exited cleanly" }
            proxyRuntimeStack.detachAll()
            stopSkippingRuntimeShutdown()
            return
        }

        val failureReason =
            when (cause) {
                is SupervisorExitCause.Crash -> {
                    Logger.e { "Proxy stopped with code ${cause.code}" }
                    FailureReason.NativeError("Proxy exited with code ${cause.code}")
                }

                is SupervisorExitCause.StartupFailure -> {
                    val error = cause.throwable
                    Logger.e(error) { "Proxy failed" }
                    classifyFailureReason(error, isTunnelContext = true)
                }

                SupervisorExitCause.Cancellation -> {
                    Logger.e { "Proxy runtime was cancelled unexpectedly" }
                    FailureReason.NativeError("Proxy runtime was cancelled unexpectedly")
                }

                SupervisorExitCause.ExpectedStop -> {
                    null
                }
            }

        reportFailure(failureReason)
        proxyRuntimeStack.detachAll()
        stopSkippingRuntimeShutdown()
    }

    suspend fun handleWarpExit(cause: SupervisorExitCause) {
        if (cause is SupervisorExitCause.ExpectedStop) {
            return
        }

        val failureReason =
            when (cause) {
                is SupervisorExitCause.Crash -> {
                    Logger.e { "WARP stopped with code ${cause.code}" }
                    FailureReason.WarpRuntimeFailed("WARP exited with code ${cause.code}")
                }

                is SupervisorExitCause.StartupFailure -> {
                    val error = cause.throwable
                    Logger.e(error) { "WARP failed" }
                    classifyFailureReason(error, isTunnelContext = true)
                }

                SupervisorExitCause.Cancellation -> {
                    Logger.e { "WARP runtime was cancelled unexpectedly" }
                    FailureReason.WarpRuntimeFailed("WARP runtime was cancelled unexpectedly")
                }

                SupervisorExitCause.ExpectedStop -> {
                    null
                }
            }

        reportFailure(failureReason)
        warpRuntimeSupervisor.detach()
        stopSkippingRuntimeShutdown()
    }

    suspend fun handleAwgExit(cause: SupervisorExitCause) {
        if (cause is SupervisorExitCause.ExpectedStop) {
            return
        }

        val failureReason =
            when (cause) {
                is SupervisorExitCause.Crash -> {
                    Logger.e { "AmneziaWG stopped with code ${cause.code}" }
                    FailureReason.NativeError("AmneziaWG exited with code ${cause.code}")
                }

                is SupervisorExitCause.StartupFailure -> {
                    val error = cause.throwable
                    Logger.e(error) { "AmneziaWG failed" }
                    classifyFailureReason(error, isTunnelContext = true)
                }

                SupervisorExitCause.Cancellation -> {
                    Logger.e { "AmneziaWG runtime was cancelled unexpectedly" }
                    FailureReason.NativeError("AmneziaWG runtime was cancelled unexpectedly")
                }

                SupervisorExitCause.ExpectedStop -> {
                    null
                }
            }

        reportFailure(failureReason)
        amneziaWgRuntimeSupervisor.detach()
        stopSkippingRuntimeShutdown()
    }

    suspend fun handleRelayExit(cause: SupervisorExitCause) {
        if (cause is SupervisorExitCause.ExpectedStop) {
            return
        }

        // The foreign relay died after connecting. The base VPN runtime is intentionally
        // kept alive (traffic now egresses DIRECT), so we do NOT stop the service — instead
        // we raise a sticky signal that flips the Home actuator from Locked to Degraded.
        logRelayExit(cause)
        markForeignRelayFailed()
        upstreamRelaySupervisor.detach()
    }

    private fun reportFailure(failureReason: FailureReason?) {
        if (failureReason != null) {
            updateStatus(ServiceStatus.Failed, failureReason)
        }
    }

    private fun stopSkippingRuntimeShutdown() {
        host.serviceScope.launch(ioDispatcher) { stopService(true) }
    }

    private fun logRelayExit(cause: SupervisorExitCause) {
        when (cause) {
            is SupervisorExitCause.Crash -> {
                Logger.e { "Relay stopped with code ${cause.code}; keeping base VPN runtime active" }
            }

            is SupervisorExitCause.StartupFailure -> {
                Logger.e(cause.throwable) { "Relay failed after startup; keeping base VPN runtime active" }
            }

            SupervisorExitCause.Cancellation -> {
                Logger.e { "Relay runtime was cancelled unexpectedly; keeping base VPN runtime active" }
            }

            SupervisorExitCause.ExpectedStop -> {}
        }
    }
}
