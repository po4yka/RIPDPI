package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import javax.inject.Inject
import javax.inject.Singleton

data class SwitchOrigin(
    val mode: Mode,
    val intentGeneration: Long,
)

@Singleton
class FailedSwitchRestorer
    @Inject
    constructor(
        private val serviceController: ServiceController,
        private val serviceStateStore: ServiceStateStore,
        private val serviceIntentArbiter: ServiceIntentArbiter,
    ) {
        private val log = Logger.withTag(LogTags.SERVICE)

        fun captureRunning(): SwitchOrigin? {
            val (status, mode) = serviceStateStore.status.value
            return if (status == AppStatus.Running) {
                SwitchOrigin(mode, serviceIntentArbiter.captureExplicitUserIntentGeneration())
            } else {
                null
            }
        }

        suspend fun restore(origin: SwitchOrigin): Boolean {
            val stillRunning = serviceStateStore.status.value.first == AppStatus.Running
            val superseded = serviceIntentArbiter.captureExplicitUserIntentGeneration() != origin.intentGeneration
            if (stillRunning || superseded) return false
            val receipt = serviceController.prepareStart(origin.mode)
            val result =
                serviceIntentArbiter.runIfExplicitUserIntentCurrent(origin.intentGeneration) {
                    serviceController.startPrepared(origin.mode, receipt)
                }
            val restored = result is ServiceStartResult.Accepted
            log.i { "Previous ${origin.mode} connection restore after failed switch: $restored" }
            return restored
        }
    }
