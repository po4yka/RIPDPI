package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkAutoProfileBindingObserver
    @Inject
    constructor(
        private val networkHandoverMonitor: NetworkHandoverMonitor,
        private val bindingApplier: NetworkAutoProfileBindingApplier,
        @param:ApplicationScope private val applicationScope: CoroutineScope,
    ) {
        private var job: Job? = null

        fun register() {
            if (job != null) return
            job =
                applicationScope.launch {
                    networkHandoverMonitor.events.collect {
                        runCatching {
                            bindingApplier.applyForCurrentNetwork()
                        }.onFailure { error ->
                            Logger.w(error) { "Network auto-profile apply failed after connectivity change" }
                        }
                    }
                }
        }
    }
