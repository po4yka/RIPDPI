package com.poyka.ripdpi.hosts

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.services.HostAutolearnStoreController
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class LearnedHostsEraseResult {
    Failed,
    Cleared,

    /** The file is gone now; the running engine keeps its copy until it restarts. */
    ClearedAfterStop,
}

/**
 * Erases the host-autolearn store so that the erase survives a running or starting engine.
 *
 * The native engine keeps the learned hosts in memory and writes them back on a debounced persist
 * and at shutdown. The erase is therefore recorded as a persisted pending marker, and the service
 * deletes the store again before the next native runtime loads it. That also covers a process
 * that is killed after a rewrite.
 */
@Singleton
class LearnedHostsEraser
    @Inject
    constructor(
        private val storeController: HostAutolearnStoreController,
        private val serviceStateStore: ServiceStateStore,
        private val dispatchers: AppCoroutineDispatchers,
    ) {
        suspend fun erase(): LearnedHostsEraseResult =
            withContext(dispatchers.io) {
                when {
                    !storeController.markErasePending() || !storeController.clearStore() -> {
                        Logger.w { "Learned-hosts erase failed; a recorded erase stays pending" }
                        LearnedHostsEraseResult.Failed
                    }

                    serviceStateStore.status.value.first == AppStatus.Halted -> {
                        LearnedHostsEraseResult.Cleared
                    }

                    else -> {
                        LearnedHostsEraseResult.ClearedAfterStop
                    }
                }
            }
    }
