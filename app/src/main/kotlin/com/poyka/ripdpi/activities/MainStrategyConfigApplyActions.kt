package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.services.ServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

internal class MainStrategyConfigApplyActions(
    private val scope: CoroutineScope,
    private val serviceStateStore: ServiceStateStore,
    private val serviceController: ServiceController,
) {
    private var restartJob: Job? = null

    fun applySavedStrategyConfig(): StrategyConfigApplyResult =
        when {
            serviceStateStore.status.value.first != AppStatus.Running -> {
                StrategyConfigApplyResult.NextSession
            }

            restartJob?.isActive == true -> {
                StrategyConfigApplyResult.RestartAlreadyPending
            }

            else -> {
                val mode = serviceStateStore.status.value.second
                restartJob =
                    scope.launch {
                        serviceController.stop()
                        if (waitForServiceStatus(AppStatus.Halted)) {
                            serviceController.start(mode)
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
