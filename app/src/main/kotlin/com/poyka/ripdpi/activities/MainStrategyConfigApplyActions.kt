package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

internal class MainStrategyConfigApplyActions(
    private val scope: CoroutineScope,
    private val appSettingsRepository: AppSettingsRepository,
    private val currentSettings: () -> AppSettings,
    private val serviceStateStore: ServiceStateStore,
    private val serviceController: ServiceController,
    private val onUnsupportedVpnDns: () -> Unit,
) {
    private var restartJob: Job? = null

    fun applySavedStrategyConfig(): StrategyConfigApplyResult =
        when {
            serviceStateStore.status.value.first != AppStatus.Running -> {
                StrategyConfigApplyResult.NextSession
            }

            serviceStateStore.status.value.second == Mode.VPN && hasUnsupportedVpnDoq(currentSettings()) -> {
                StrategyConfigApplyResult.UnsupportedVpnDns
            }

            restartJob?.isActive == true -> {
                StrategyConfigApplyResult.RestartAlreadyPending
            }

            else -> {
                val mode = serviceStateStore.status.value.second
                restartJob =
                    scope.launch {
                        if (mode == Mode.VPN && hasUnsupportedVpnDoq(appSettingsRepository.snapshot())) {
                            onUnsupportedVpnDns()
                            return@launch
                        }
                        serviceController.stop()
                        if (waitForServiceStatus(AppStatus.Halted)) {
                            when (serviceController.start(mode)) {
                                is ServiceStartResult.Accepted -> Unit
                                is ServiceStartResult.Rejected -> Unit
                            }
                        }
                    }
                StrategyConfigApplyResult.RestartingActiveService
            }
        }

    private suspend fun waitForServiceStatus(target: AppStatus): Boolean =
        withTimeoutOrNull(10.seconds) {
            serviceStateStore.status.first { it.first == target }
            true
        } == true
}
