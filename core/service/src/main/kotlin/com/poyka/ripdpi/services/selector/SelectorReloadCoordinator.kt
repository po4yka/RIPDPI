package com.poyka.ripdpi.services.selector

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.Mode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Drives a hot reload of the running relay supervisor when a selector group's
 * active member changes.
 *
 * NekoBox tears down nothing on a selector switch — the sing-box selector
 * outbound is swapped in place via `cbSelectorUpdate`. RIPDPI reproduces that
 * with a [SelectorReloadTrigger.hotReload]: a selection change reloads the
 * relay runtime with the newly-selected profile, never a full
 * [SelectorReloadTrigger.teardown].
 */
interface SelectorReloadTrigger {
    /**
     * Hot-reloads the running relay supervisor so traffic flows through the
     * member profile identified by [request]. Must not tear the service down.
     */
    suspend fun hotReload(request: SelectorReloadRequest)

    /** Full service tear-down — only for non-hot-reloadable transitions. */
    suspend fun teardown()
}

/**
 * Watches a selector group's [selectionChanges] signal and, while
 * [start]ed, calls [SelectorReloadTrigger.hotReload] on every *change* to the
 * selection.
 *
 * Semantics:
 * - The initial (seed) value never triggers a reload — only subsequent changes
 *   do, so wiring the coordinator up does not bounce a freshly-started service.
 * - Repeated emissions of the same captured request are deduplicated; a new manual reservation
 *   can reload the same member.
 * - A `null` selection (the group has no active member) is skipped — there is
 *   nothing to reload to.
 * - [stop] cancels the watch; later selection changes are ignored.
 */
class SelectorReloadCoordinator(
    private val scope: CoroutineScope,
    private val selectionChanges: Flow<SelectorReloadRequest?>,
    private val trigger: SelectorReloadTrigger,
) {
    private var watchJob: Job? = null
    private val owners = mutableSetOf<Mode>()
    private var subscriptionReady = CompletableDeferred<Unit>()

    /** Waits for the seed before startup snapshots; cancellation before a seed fails preparation. */
    suspend fun awaitSubscription() = subscriptionReady.await()

    /** Begins watching the selection signal. Idempotent: a second call is a no-op. */
    @Suppress("TooGenericExceptionCaught")
    fun start(owner: Mode) {
        owners.add(owner)
        if (watchJob?.isActive == true) return
        val ready = CompletableDeferred<Unit>()
        subscriptionReady = ready
        watchJob =
            scope.launch {
                try {
                    selectionChanges
                        .distinctUntilChanged()
                        .onEach { ready.complete(Unit) }
                        // Startup waits for this seed before reading the durable selection.
                        .drop(1)
                        .filterNotNull()
                        .collect { request ->
                            try {
                                trigger.hotReload(request)
                            } catch (cancelled: CancellationException) {
                                currentCoroutineContext().ensureActive()
                                Logger.e(cancelled) { "Selector runtime reload timed out" }
                            } catch (failure: Exception) {
                                Logger.e(failure) { "Selector runtime reload failed" }
                            }
                        }
                } finally {
                    ready.cancel()
                }
            }
        watchJob?.invokeOnCompletion { ready.cancel() }
    }

    /** Stops watching the selection signal; subsequent changes are ignored. */
    fun stop(owner: Mode) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        watchJob?.cancel()
        watchJob = null
    }
}
