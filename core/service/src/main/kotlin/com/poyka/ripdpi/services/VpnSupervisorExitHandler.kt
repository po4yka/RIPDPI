package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.classifyFailureReason

internal class VpnSupervisorExitHandler(
    private val proxyRuntimeStack: SharedProxyRuntimeStack,
    private val upstreamRelaySupervisor: UpstreamRelaySupervisor,
    private val warpRuntimeSupervisor: WarpRuntimeSupervisor,
    private val amneziaWgRuntimeSupervisor: AmneziaWgRuntimeSupervisor,
    private val markForeignRelayFailed: () -> Unit,
    // Fails (when the reason is set) and stops the session that is current when the exit is seen.
    private val failAndStop: (FailureReason?) -> Unit,
) {
    suspend fun handleProxyExit(cause: SupervisorExitCause) {
        if (cause is SupervisorExitCause.ExpectedStop) {
            return
        }
        if (cause is SupervisorExitCause.Crash && cause.code == 0) {
            Logger.i { "VPN local proxy exited cleanly" }
            proxyRuntimeStack.detachCompletedRuntimes()
            failAndStop(null)
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

        proxyRuntimeStack.detachCompletedRuntimes()
        failAndStop(failureReason)
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

        warpRuntimeSupervisor.detach()
        failAndStop(failureReason)
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

        amneziaWgRuntimeSupervisor.detach()
        failAndStop(failureReason)
    }

    suspend fun handleRelayExit(cause: SupervisorExitCause) {
        if (cause is SupervisorExitCause.ExpectedStop) {
            return
        }

        val failureReason =
            when (cause) {
                is SupervisorExitCause.Crash -> {
                    Logger.e { "Relay stopped with code ${cause.code}; stopping VPN fail-closed" }
                    FailureReason.NativeError("Relay exited with code ${cause.code}")
                }

                is SupervisorExitCause.StartupFailure -> {
                    val error = cause.throwable
                    Logger.e(error) { "Relay failed after startup; stopping VPN fail-closed" }
                    classifyFailureReason(error, isTunnelContext = true)
                }

                SupervisorExitCause.Cancellation -> {
                    Logger.e { "Relay runtime was cancelled unexpectedly; stopping VPN fail-closed" }
                    FailureReason.NativeError("Relay runtime was cancelled unexpectedly")
                }

                SupervisorExitCause.ExpectedStop -> {
                    null
                }
            }

        markForeignRelayFailed()
        upstreamRelaySupervisor.detach()
        failAndStop(failureReason)
    }
}
