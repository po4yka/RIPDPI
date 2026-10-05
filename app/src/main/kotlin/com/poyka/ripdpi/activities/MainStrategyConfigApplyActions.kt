package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.RunningReconnectResult
import com.poyka.ripdpi.services.RunningServiceReconnect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class MainStrategyConfigApplyActions(
    private val scope: CoroutineScope,
    private val appSettingsRepository: AppSettingsRepository,
    private val currentSettings: () -> AppSettings,
    private val serviceStateStore: ServiceStateStore,
    private val reconnectCoordinator: RunningServiceReconnect,
    private val onReconnectFailure: (RunningReconnectResult.Failed) -> Unit,
    private val onUnsupportedVpnDns: () -> Unit,
) {
    private var restartJob: Job? = null

    fun applySavedStrategyConfig(
        request: com.poyka.ripdpi.services.RunningReconnectRequest,
    ): StrategyConfigApplyResult =
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
                val mode = request.mode
                restartJob =
                    scope.launch {
                        if (mode == Mode.VPN && hasUnsupportedVpnDoq(appSettingsRepository.snapshot())) {
                            onUnsupportedVpnDns()
                            return@launch
                        }
                        val result = reconnectCoordinator.reconnect(request)
                        if (result is RunningReconnectResult.Failed) onReconnectFailure(result)
                    }
                StrategyConfigApplyResult.RestartingActiveService
            }
        }

    fun cancelReconnect() {
        reconnectCoordinator.cancelReconnect()
        restartJob?.cancel()
    }
}
