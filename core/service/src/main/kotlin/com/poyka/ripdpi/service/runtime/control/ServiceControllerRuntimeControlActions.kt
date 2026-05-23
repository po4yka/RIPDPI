package com.poyka.ripdpi.service.runtime.control

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.services.ServiceController
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-level [RuntimeControlActions] — services the runtime *lifecycle*
 * commands by delegating to [ServiceController], the process-wide start/stop
 * intent issuer.
 *
 * - [startRuntime] / [stopRuntime] delegate 1:1 to [ServiceController.start] /
 *   [ServiceController.stop]; behavior is identical to calling the controller
 *   directly.
 * - [restartRuntime] issues a stop, waits (best-effort) for the runtime to halt
 *   so the start is not reordered ahead of the stop, then starts the same mode.
 *
 * The in-session commands ([applyNetworkSnapshot], [applyPolicyPatch],
 * [refreshRelay], [refreshDns]) target a live per-mode runtime coordinator that
 * the process-level seam cannot reach; they return [RuntimeControlOutcome.Ignored].
 * A session-scoped [RuntimeControlActions] is the intended home for them as the
 * seam is adopted further — until then those paths keep their direct coordinator
 * calls.
 */
@Singleton
internal class ServiceControllerRuntimeControlActions
    @Inject
    constructor(
        private val serviceController: ServiceController,
        private val serviceStateStore: ServiceStateStore,
    ) : RuntimeControlActions {
        override suspend fun startRuntime(
            mode: Mode,
            reason: RuntimeControlReason,
        ): RuntimeControlOutcome {
            serviceController.start(mode)
            return RuntimeControlOutcome.Completed
        }

        override suspend fun stopRuntime(reason: RuntimeControlReason): RuntimeControlOutcome {
            serviceController.stop()
            return RuntimeControlOutcome.Completed
        }

        override suspend fun restartRuntime(reason: RuntimeControlReason): RuntimeControlOutcome {
            val (status, mode) = serviceStateStore.status.value
            if (status == AppStatus.Running) {
                serviceController.stop()
                awaitHalted()
            }
            serviceController.start(mode)
            return RuntimeControlOutcome.Completed
        }

        override suspend fun applyNetworkSnapshot(reason: RuntimeControlReason): RuntimeControlOutcome =
            sessionScopedCommandIgnored("ApplyNetworkSnapshot")

        override suspend fun applyPolicyPatch(
            reason: RuntimeControlReason,
            patch: RuntimePolicyPatch,
        ): RuntimeControlOutcome = sessionScopedCommandIgnored("ApplyPolicyPatch")

        override suspend fun refreshRelay(reason: RuntimeControlReason): RuntimeControlOutcome =
            sessionScopedCommandIgnored("RefreshRelay")

        override suspend fun refreshDns(reason: RuntimeControlReason): RuntimeControlOutcome =
            sessionScopedCommandIgnored("RefreshDns")

        /** Best-effort wait for the runtime to reach [AppStatus.Halted] before a restart's start leg. */
        private suspend fun awaitHalted() {
            repeat(RestartHaltPollAttempts) {
                if (serviceStateStore.status.value.first == AppStatus.Halted) {
                    return
                }
                delay(RestartHaltPollDelayMs)
            }
        }

        private fun sessionScopedCommandIgnored(command: String): RuntimeControlOutcome =
            RuntimeControlOutcome.Ignored(
                "$command targets a live in-session runtime coordinator and is not serviced by the " +
                    "process-level control plane; that path keeps its direct coordinator call for now",
            )

        private companion object {
            private const val RestartHaltPollAttempts = 50
            private const val RestartHaltPollDelayMs = 100L
        }
    }
