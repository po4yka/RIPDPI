package com.poyka.ripdpi.services.selector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
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
     * member profile identified by [profileId]. Must not tear the service down.
     */
    suspend fun hotReload(profileId: String)

    /** Full service tear-down — only for non-hot-reloadable transitions. */
    suspend fun teardown()
}

/**
 * Watches a selector group's [selectedProfileId] signal and, while
 * [start]ed, calls [SelectorReloadTrigger.hotReload] on every *change* to the
 * selection.
 *
 * Semantics:
 * - The initial (seed) value never triggers a reload — only subsequent changes
 *   do, so wiring the coordinator up does not bounce a freshly-started service.
 * - Repeated emissions of the same id are deduplicated; a no-op re-selection
 *   does not cause a redundant reload.
 * - A `null` selection (the group has no active member) is skipped — there is
 *   nothing to reload to.
 * - [stop] cancels the watch; later selection changes are ignored.
 */
class SelectorReloadCoordinator(
    private val scope: CoroutineScope,
    private val selectedProfileId: Flow<String?>,
    private val trigger: SelectorReloadTrigger,
) {
    private var watchJob: Job? = null

    /** Begins watching the selection signal. Idempotent: a second call is a no-op. */
    fun start() {
        if (watchJob != null) return
        watchJob =
            scope.launch {
                selectedProfileId
                    .distinctUntilChanged()
                    // Drop the seed value: only honest changes drive a reload.
                    .drop(1)
                    .filterNotNull()
                    .collect { profileId ->
                        trigger.hotReload(profileId)
                    }
            }
    }

    /** Stops watching the selection signal; subsequent changes are ignored. */
    fun stop() {
        watchJob?.cancel()
        watchJob = null
    }
}
