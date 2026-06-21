package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.classifyFailureReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch

internal class ProxySupervisorExitHandler(
    private val host: ServiceCoordinatorHost,
    private val ioDispatcher: CoroutineDispatcher,
    private val upstreamRelaySupervisor: UpstreamRelaySupervisor,
    private val warpRuntimeSupervisor: WarpRuntimeSupervisor,
    private val amneziaWgRuntimeSupervisor: AmneziaWgRuntimeSupervisor,
    private val proxyRuntimeSupervisor: ProxyRuntimeSupervisor,
    private val updateStatus: (ServiceStatus, FailureReason?) -> Unit,
    private val stopService: suspend (skipRuntimeShutdown: Boolean) -> Unit,
) {
    suspend fun handleProxyExit(cause: SupervisorExitCause) {
        if (cause is SupervisorExitCause.ExpectedStop) {
            return
        }
        if (cause is SupervisorExitCause.Crash && cause.code == 0) {
            Logger.i { "Proxy exited cleanly" }
            proxyRuntimeSupervisor.detach()
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
                    classifyFailureReason(error)
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
        proxyRuntimeSupervisor.detach()
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
                    classifyFailureReason(error)
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

    suspend fun handleRelayExit(cause: SupervisorExitCause) {
        if (cause is SupervisorExitCause.ExpectedStop) {
            return
        }

        logRelayExit(cause)
        upstreamRelaySupervisor.detach()
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
                    classifyFailureReason(error)
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
                Logger.e { "Relay stopped with code ${cause.code}; keeping base proxy runtime active" }
            }

            is SupervisorExitCause.StartupFailure -> {
                Logger.e(cause.throwable) { "Relay failed after startup; keeping base proxy runtime active" }
            }

            SupervisorExitCause.Cancellation -> {
                Logger.e { "Relay runtime was cancelled unexpectedly; keeping base proxy runtime active" }
            }

            SupervisorExitCause.ExpectedStop -> {
                Unit
            }
        }
    }
}
