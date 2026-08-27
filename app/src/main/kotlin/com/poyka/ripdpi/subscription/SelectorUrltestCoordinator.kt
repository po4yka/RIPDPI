package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Singleton

/**
 * Runs a [SelectorUrltestProber] loop for every selector group that carries a
 * urltest [com.poyka.ripdpi.data.SelectorFailover], for as long as the coordinator
 * is [start]ed. Tracks the group set reactively (via
 * [ProxyGroupRepository.groups]) so a newly-imported or refreshed urltest group
 * starts probing without a service restart, and a removed one stops.
 *
 * The prober updates the persisted selection on a latency win, which the wired
 * [com.poyka.ripdpi.services.selector.SelectorReloadCoordinator] turns into a live
 * exit hot-reload — closing the loop from "urltest policy parsed" to "live exit
 * switched on latency".
 */
@Singleton
class SelectorUrltestCoordinator(
    private val scope: CoroutineScope,
    private val groupRepository: ProxyGroupRepository,
    private val prober: SelectorUrltestProber,
) {
    private var watchJob: Job? = null
    private val proberJobs = mutableMapOf<String, RunningProbe>()

    /** Begins watching for urltest selector groups. Idempotent. */
    fun start() {
        if (watchJob != null) return
        watchJob =
            scope.launch {
                groupRepository
                    .groups()
                    .map(::urltestGroups)
                    .distinctUntilChanged()
                    .collect(::reconcile)
            }
    }

    /** Stops watching and cancels every running prober loop. */
    fun stop() {
        watchJob?.cancel()
        watchJob = null
        proberJobs.forEach { (id, running) ->
            running.job.cancel()
            prober.invalidatePendingSelection(id)
        }
        proberJobs.clear()
    }

    private fun urltestGroups(groups: List<ProxyGroup>): List<ProxyGroup> =
        groups.filter { it.failover != null && it.members.size >= MinMembers }

    private fun reconcile(groups: List<ProxyGroup>) {
        val wanted = groups.associateBy { it.id }
        // Cancel probers for groups that disappeared or lost their urltest policy.
        (proberJobs.keys - wanted.keys).forEach { id ->
            proberJobs.remove(id)?.job?.cancel()
            prober.invalidatePendingSelection(id)
        }
        // Refresh only the inputs used by probing; timestamp/accounting changes must not
        // restart the interval and indefinitely postpone the next probe.
        wanted.forEach { (id, group) ->
            val previous = proberJobs[id]
            if (previous == null || !previous.job.isActive || previous.policyChanged(group)) {
                // Cancel first: the old loop must not acquire a fresh valid snapshot
                // in the interval between invalidation and cancellation.
                previous?.job?.cancel()
                prober.invalidatePendingSelection(id)
                proberJobs[id] = RunningProbe(group, scope.launch { prober.run(group) })
            }
        }
    }

    private data class RunningProbe(
        val group: ProxyGroup,
        val job: Job,
    ) {
        fun policyChanged(updated: ProxyGroup): Boolean =
            group.members != updated.members || group.failover != updated.failover ||
                group.cloudflareMemberIds != updated.cloudflareMemberIds
    }

    private companion object {
        const val MinMembers = 2
    }
}
