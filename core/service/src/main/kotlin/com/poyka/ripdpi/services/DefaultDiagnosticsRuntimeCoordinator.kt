package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.toSettingsSections
import com.poyka.ripdpi.service.runtime.RuntimeModeProjectionStore
import com.poyka.ripdpi.service.runtime.control.RuntimeControlCommand
import com.poyka.ripdpi.service.runtime.control.RuntimeControlPlane
import com.poyka.ripdpi.service.runtime.control.RuntimeControlReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
 *
 * For the duration of a scan the coordinator publishes the diagnostics-scan
 * layer into [RuntimeModeProjectionStore] and logs the runtime mode the scan
 * runs against. That is read-only observability — it changes neither the scan
 * nor any service start/stop transition.
 */
@Singleton
internal class DefaultDiagnosticsRuntimeCoordinator
    @Inject
    constructor(
        private val runtimeControlPlane: RuntimeControlPlane,
        private val runtimeModeProjectionStore: RuntimeModeProjectionStore,
        private val serviceStateStore: ServiceStateStore,
        private val appSettingsRepository: AppSettingsRepository,
    ) : DiagnosticsRuntimeCoordinator {
        private var waitAttempts: Int = 50
        private var waitDelayMs: Long = 200L

        internal constructor(
            runtimeControlPlane: RuntimeControlPlane,
            runtimeModeProjectionStore: RuntimeModeProjectionStore,
            serviceStateStore: ServiceStateStore,
            appSettingsRepository: AppSettingsRepository,
            waitAttempts: Int,
            waitDelayMs: Long,
        ) : this(runtimeControlPlane, runtimeModeProjectionStore, serviceStateStore, appSettingsRepository) {
            this.waitAttempts = waitAttempts
            this.waitDelayMs = waitDelayMs
        }

        override suspend fun runRawPathScan(block: suspend () -> Unit) {
            reportingScanActivity("Raw-path scan") {
                val (status, mode) = serviceStateStore.status.value
                val diagnostics = appSettingsRepository.snapshot().toSettingsSections().diagnostics
                val shouldResume = status == AppStatus.Running && diagnostics.diagnosticsAutoResumeAfterRawScan

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
        }

        override suspend fun runAutomaticRawPathScan(block: suspend () -> Unit) {
            reportingScanActivity("Automatic raw-path scan") {
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
        }

        /**
         * Run a raw-path scan while keeping [RuntimeModeProjectionStore] in step:
         * publish the diagnostics-scan layer as active for the scan's duration,
         * and log the runtime mode the scan is operating against.
         *
         * This is read-only observability wrapped around the existing scan — it
         * changes neither the scan nor any service start/stop transition.
         */
        private suspend fun reportingScanActivity(
            label: String,
            scan: suspend () -> Unit,
        ) {
            runtimeModeProjectionStore.markDiagnosticsScanActive(true)
            try {
                val projection = runtimeModeProjectionStore.projection.first()
                Logger.d { "$label starting; runtime mode = $projection" }
                scan()
            } finally {
                runtimeModeProjectionStore.markDiagnosticsScanActive(false)
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
