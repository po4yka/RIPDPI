package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

@Singleton
class DefaultDiagnosticsRuntimeCoordinator
    @Inject
    constructor(
        private val serviceController: ServiceController,
        private val serviceStateStore: ServiceStateStore,
        private val appSettingsRepository: AppSettingsRepository,
    ) : DiagnosticsRuntimeCoordinator {
        private var waitAttempts: Int = 50
        private var waitDelayMs: Long = 200L

        internal constructor(
            serviceController: ServiceController,
            serviceStateStore: ServiceStateStore,
            appSettingsRepository: AppSettingsRepository,
            waitAttempts: Int,
            waitDelayMs: Long,
        ) : this(serviceController, serviceStateStore, appSettingsRepository) {
            this.waitAttempts = waitAttempts
            this.waitDelayMs = waitDelayMs
        }

    override suspend fun runRawPathScan(block: suspend () -> Unit) {
        val (status, mode) = serviceStateStore.status.value
        val shouldResume = status == AppStatus.Running && appSettingsRepository.snapshot().diagnosticsAutoResumeAfterRawScan

        if (status == AppStatus.Running) {
            serviceController.stop()
            waitForStatus(AppStatus.Halted)
        }

        try {
            block()
        } finally {
            if (shouldResume) {
                serviceController.start(mode)
                waitForStatus(AppStatus.Running)
            }
        }
    }

    override suspend fun runAutomaticRawPathScan(block: suspend () -> Unit) {
        val (status, mode) = serviceStateStore.status.value
        val shouldResume = status == AppStatus.Running

        if (status == AppStatus.Running) {
            serviceController.stop()
            waitForStatus(AppStatus.Halted)
        }

        try {
            block()
        } finally {
            if (shouldResume) {
                serviceController.start(mode)
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
        throw IllegalStateException("Timed out waiting for service status $target")
    }
}
