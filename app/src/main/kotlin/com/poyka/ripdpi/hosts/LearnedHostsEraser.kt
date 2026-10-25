package com.poyka.ripdpi.hosts

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.services.HostAutolearnStoreController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class LearnedHostsEraseResult {
    Failed,
    Cleared,

    /** The file is gone now and is deleted again after the running engine stops. */
    ClearedAfterStop,
}

/**
 * Erases the host-autolearn store so that the erase survives a running engine.
 *
 * The native engine keeps the learned hosts in memory and rewrites the store file from that
 * copy on a debounced persist and again at shutdown. A delete while a runtime is active is
 * therefore repeated once the service reaches [AppStatus.Halted], which the service publishes
 * only after the native runtime has stopped and flushed.
 */
@Singleton
class LearnedHostsEraser
    @Inject
    constructor(
        private val storeController: HostAutolearnStoreController,
        private val serviceStateStore: ServiceStateStore,
        @param:ApplicationIoScope private val scope: CoroutineScope,
    ) {
        private var pendingErase: Job? = null

        @Synchronized
        fun erase(): LearnedHostsEraseResult {
            val result =
                when {
                    !storeController.clearStore() -> LearnedHostsEraseResult.Failed
                    serviceStateStore.status.value.first == AppStatus.Halted -> LearnedHostsEraseResult.Cleared
                    else -> LearnedHostsEraseResult.ClearedAfterStop
                }
            if (result == LearnedHostsEraseResult.ClearedAfterStop && pendingErase?.isActive != true) {
                pendingErase =
                    scope.launch {
                        serviceStateStore.status.first { (status, _) -> status == AppStatus.Halted }
                        storeController.clearStore()
                    }
            }
            return result
        }
    }
