package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.service.runtime.control.RuntimeControlCommand
import com.poyka.ripdpi.service.runtime.control.RuntimeControlPlane
import com.poyka.ripdpi.service.runtime.control.RuntimeControlReason
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service-side bridge to the diagnostics scan engine — sequences a raw-path
 * scan against service state: stops a running service before the scan and
 * auto-resumes it afterward when the `diagnosticsAutoResumeAfterRawScan`
 * setting is on. See `docs/architecture/DIAGNOSTICS_ARCHITECTURE.md`.
 *
 * Service start/stop transitions are issued through [RuntimeControlPlane] —
 * the preferred seam for runtime-change requests — rather than calling the
 * service controller directly.
 */
@Singleton
internal class DefaultDiagnosticsRuntimeCoordinator
    @Inject
    constructor(
        private val runtimeControlPlane: RuntimeControlPlane,
        private val serviceStateStore: ServiceStateStore,
        private val appSettingsRepository: AppSettingsRepository,
    ) : DiagnosticsRuntimeCoordinator {
        private var waitAttempts: Int = 50
        private var waitDelayMs: Long = 200L

        internal constructor(
            runtimeControlPlane: RuntimeControlPlane,
            serviceStateStore: ServiceStateStore,
            appSettingsRepository: AppSettingsRepository,
            waitAttempts: Int,
            waitDelayMs: Long,
        ) : this(runtimeControlPlane, serviceStateStore, appSettingsRepository) {
            this.waitAttempts = waitAttempts
            this.waitDelayMs = waitDelayMs
        }

        override suspend fun runRawPathScan(block: suspend () -> Unit) {
            val (status, mode) = serviceStateStore.status.value
            val shouldResume =
                status == AppStatus.Running && appSettingsRepository.snapshot().diagnosticsAutoResumeAfterRawScan

            if (status == AppStatus.Running) {
                runtimeControlPlane.execute(
                    RuntimeControlCommand.StopRuntime(RuntimeControlReason.DiagnosticsRawPathScan),
                )
                waitForStatus(AppStatus.Halted)
            }

            try {
                block()
            } finally {
                if (shouldResume) {
                    runtimeControlPlane.execute(
                        RuntimeControlCommand.StartRuntime(mode, RuntimeControlReason.DiagnosticsRawPathScan),
                    )
                    waitForStatus(AppStatus.Running)
                }
            }
        }

        override suspend fun runAutomaticRawPathScan(block: suspend () -> Unit) {
            val (status, mode) = serviceStateStore.status.value
            val shouldResume = status == AppStatus.Running

            if (status == AppStatus.Running) {
                runtimeControlPlane.execute(
                    RuntimeControlCommand.StopRuntime(RuntimeControlReason.DiagnosticsRawPathScan),
                )
                waitForStatus(AppStatus.Halted)
            }

            try {
                block()
            } finally {
                if (shouldResume) {
                    runtimeControlPlane.execute(
                        RuntimeControlCommand.StartRuntime(mode, RuntimeControlReason.DiagnosticsRawPathScan),
                    )
                    waitForStatus(AppStatus.Running)
                }
            }
        }

        private suspend fun waitForStatus(target: AppStatus) {
            repeat(waitAttempts) {
                if (serviceStateStore.status.value.first == target) {
                    return
                }
                delay(waitDelayMs)
            }
            error("Timed out waiting for service status $target")
        }
    }
