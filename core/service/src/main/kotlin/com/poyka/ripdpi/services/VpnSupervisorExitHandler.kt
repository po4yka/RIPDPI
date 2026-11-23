package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.classifyFailureReason

internal class VpnSupervisorExitHandler(
    private val proxyRuntimeStack: SharedProxyRuntimeStack,
    private val upstreamRelaySupervisor: UpstreamRelaySupervisor,
    private val warpRuntimeSupervisor: WarpRuntimeSupervisor,
    private val amneziaWgRuntimeSupervisor: AmneziaWgRuntimeSupervisor,
    private val markForeignRelayFailed: () -> Unit,
    // Fails the session current when the exit is seen; a null reason is a plain stop.
    private val failAndStop: (FailureReason?) -> Unit,
) {
    suspend fun handleProxyExit(cause: SupervisorExitCause) {
        if (cause is SupervisorExitCause.ExpectedStop) {
            return
        }
        if (cause is SupervisorExitCause.Crash && cause.code == 0) {
            Logger.i(tag = LogTags.SERVICE) { "VPN local proxy exited cleanly" }
            proxyRuntimeStack.detachCompletedRuntimes()
            failAndStop(null)
            return
        }

        val failureReason =
            when (cause) {
                is SupervisorExitCause.Crash -> {
                    Logger.e(tag = LogTags.SERVICE) { "Proxy stopped with code ${cause.code}" }
                    FailureReason.NativeError("Proxy exited with code ${cause.code}")
                }

                is SupervisorExitCause.StartupFailure -> {
                    val error = cause.throwable
                    Logger.e(error, tag = LogTags.SERVICE) { "Proxy failed" }
                    classifyFailureReason(error, isTunnelContext = true)
                }

                SupervisorExitCause.Cancellation -> {
                    Logger.e(tag = LogTags.SERVICE) { "Proxy runtime was cancelled unexpectedly" }
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
                    Logger.e(tag = LogTags.SERVICE) { "WARP stopped with code ${cause.code}" }
                    FailureReason.WarpRuntimeFailed("WARP exited with code ${cause.code}")
                }

                is SupervisorExitCause.StartupFailure -> {
                    val error = cause.throwable
                    Logger.e(error, tag = LogTags.SERVICE) { "WARP failed" }
                    classifyFailureReason(error, isTunnelContext = true)
                }

                SupervisorExitCause.Cancellation -> {
                    Logger.e(tag = LogTags.SERVICE) { "WARP runtime was cancelled unexpectedly" }
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
                    Logger.e(tag = LogTags.SERVICE) { "AmneziaWG stopped with code ${cause.code}" }
                    FailureReason.NativeError("AmneziaWG exited with code ${cause.code}")
                }

                is SupervisorExitCause.StartupFailure -> {
                    val error = cause.throwable
                    Logger.e(error, tag = LogTags.SERVICE) { "AmneziaWG failed" }
                    classifyFailureReason(error, isTunnelContext = true)
                }

                SupervisorExitCause.Cancellation -> {
                    Logger.e(tag = LogTags.SERVICE) { "AmneziaWG runtime was cancelled unexpectedly" }
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
                    Logger.e(tag = LogTags.SERVICE) { "Relay stopped with code ${cause.code}" }
                    FailureReason.NativeError("Relay exited with code ${cause.code}")
                }

                is SupervisorExitCause.StartupFailure -> {
                    val error = cause.throwable
                    Logger.e(error, tag = LogTags.SERVICE) { "Relay failed after startup" }
                    classifyFailureReason(error, isTunnelContext = true)
                }

                SupervisorExitCause.Cancellation -> {
                    Logger.e(tag = LogTags.SERVICE) { "Relay runtime was cancelled unexpectedly" }
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
