package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.classifyFailureReason

internal class ProxySupervisorExitHandler(
    private val upstreamRelaySupervisor: UpstreamRelaySupervisor,
    private val warpRuntimeSupervisor: WarpRuntimeSupervisor,
    private val amneziaWgRuntimeSupervisor: AmneziaWgRuntimeSupervisor,
    private val proxyRuntimeSupervisor: ProxyRuntimeSupervisor,
    private val markForeignRelayFailed: () -> Unit,
    // Fails (when the reason is set) and stops the session that is current when the exit is seen.
    private val failAndStop: (FailureReason?) -> Unit,
) {
    suspend fun handleProxyExit(cause: SupervisorExitCause) {
        if (cause is SupervisorExitCause.ExpectedStop) {
            return
        }
        if (cause is SupervisorExitCause.Crash && cause.code == 0) {
            Logger.i(tag = LogTags.SERVICE) { "Proxy exited cleanly" }
            proxyRuntimeSupervisor.detach()
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
                    classifyFailureReason(error)
                }

                SupervisorExitCause.Cancellation -> {
                    Logger.e(tag = LogTags.SERVICE) { "Proxy runtime was cancelled unexpectedly" }
                    FailureReason.NativeError("Proxy runtime was cancelled unexpectedly")
                }

                SupervisorExitCause.ExpectedStop -> {
                    null
                }
            }

        proxyRuntimeSupervisor.detach()
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
                    classifyFailureReason(error)
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

    suspend fun handleRelayExit(cause: SupervisorExitCause) {
        if (cause is SupervisorExitCause.ExpectedStop) {
            return
        }

        // The foreign relay died after connecting. The base proxy is intentionally kept
        // alive (traffic now egresses DIRECT), so we do NOT stop the service — instead we
        // raise a sticky signal that flips the Home actuator from Locked to Degraded.
        logRelayExit(cause)
        markForeignRelayFailed()
        upstreamRelaySupervisor.detach()
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
                    classifyFailureReason(error)
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

    private fun logRelayExit(cause: SupervisorExitCause) {
        when (cause) {
            is SupervisorExitCause.Crash -> {
                Logger.e(
                    tag = LogTags.SERVICE,
                ) { "Relay stopped with code ${cause.code}; keeping base proxy runtime active" }
            }

            is SupervisorExitCause.StartupFailure -> {
                Logger.e(
                    cause.throwable,
                    tag = LogTags.SERVICE,
                ) { "Relay failed after startup; keeping base proxy runtime active" }
            }

            SupervisorExitCause.Cancellation -> {
                Logger.e(
                    tag = LogTags.SERVICE,
                ) { "Relay runtime was cancelled unexpectedly; keeping base proxy runtime active" }
            }

            SupervisorExitCause.ExpectedStop -> {}
        }
    }
}
