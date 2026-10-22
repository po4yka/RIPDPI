package com.poyka.ripdpi.subscription

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.PayloadFailoverPolicy
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.selector.SelectorSelectionSnapshot
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/** A measured latency for a selector member, or `null` when the member is unreachable. */
data class MemberLatency(
    val profileId: String,
    val latencyMillis: Long?,
)

sealed interface MemberProbeTarget {
    data class Reachability(
        val url: String,
    ) : MemberProbeTarget

    data class Payload(
        val policy: PayloadFailoverPolicy,
    ) : MemberProbeTarget
}

/**
 * Measures a single selector member's reachability latency against the group's
 * `probeUrl`. Injectable so the prober is testable without a real network: the
 * production implementation verifies a complete bounded HTTP payload through the candidate relay.
 */
fun interface MemberLatencyProbe {
    /**
     * Returns the round-trip latency in milliseconds for [profile] probed against
     * [target], or `null` when the member did not respond.
     */
    suspend fun measure(
        profile: ProxyProfile,
        target: MemberProbeTarget,
    ): Long?
}

/**
 * The urltest consumer that was missing (audit finding P1-8): a selector group
 * carrying a [com.poyka.ripdpi.data.SelectorFailover] now drives a real periodic
 * latency probe. Every `intervalSeconds`, each member is probed; when the
 * lowest-latency member beats the currently-selected member by more than
 * `toleranceMs`, the selection is updated through [SelectorSelectionStore.selectAutomatically]
 * — which the wired [com.poyka.ripdpi.services.selector.SelectorReloadCoordinator]
 * turns into a live hot-reload of the exit.
 *
 * Timing is driven by [kotlinx.coroutines.delay] so a `runTest` virtual clock
 * exercises every cadence without real waiting, and the network is behind
 * [MemberLatencyProbe], so the prober is fully testable offline.
 */
@Singleton
class SelectorUrltestProber internal constructor(
    private val selectionStore: SelectorSelectionStore,
    private val probe: MemberLatencyProbe,
    private val scopeProvider: SelectorProbeScopeProvider,
    private val monotonicMillis: () -> Long = { System.nanoTime() / NanosecondsPerMillisecond },
) {
    private val log = Logger.withTag("selector-urltest")
    private val payloadStates = ConcurrentHashMap<String, ScopedPayloadState>()

    private data class ScopedPayloadState(
        val scope: SelectorProbeScope,
        var selection: SelectorSelectionSnapshot,
        val state: PayloadFailoverState,
        var reserveIndex: Int = 0,
        var recoveringReserve: String? = null,
        val failedReserves: MutableSet<String> = mutableSetOf(),
    )

    /** Fences a replaced or stopped policy against a probe already about to commit. */
    internal fun invalidatePendingSelection(groupId: String) {
        payloadStates.remove(groupId)
        selectionStore.invalidatePendingSelection(groupId)
    }

    /**
     * Runs the probe loop for [group] until the surrounding coroutine is
     * cancelled. A no-op (returns immediately) when the group has no urltest
     * [com.poyka.ripdpi.data.SelectorFailover] or fewer than two members — there
     * is nothing to fail over between.
     */
    suspend fun run(group: ProxyGroup) {
        val failover = group.failover ?: return
        if (group.members.size < MinMembers) return
        val intervalMillis =
            failover.intervalSeconds
                .coerceAtLeast(MinIntervalSeconds)
                .seconds.inWholeMilliseconds
        try {
            while (true) {
                delay(intervalMillis)
                runProbePass(group, failover.probeUrl, failover.toleranceMs)
            }
        } finally {
            invalidatePendingSelection(group.id)
        }
    }

    /**
     * One probe pass: measures every member, then applies the
     * [bestSwitchCandidate] decision. Public so a test can drive a single pass
     * deterministically without the loop.
     */
    suspend fun runProbePass(
        group: ProxyGroup,
        probeUrl: String,
        toleranceMs: Int,
    ) {
        val policy = group.failover?.payloadPolicy
        if (policy != null) {
            runPayloadPass(group, policy)
        } else {
            runReachabilityPass(group, probeUrl, toleranceMs)
        }
    }

    private suspend fun runReachabilityPass(
        group: ProxyGroup,
        probeUrl: String,
        toleranceMs: Int,
    ) {
        val scope = scopeProvider.capture(group) ?: return
        val selection = selectionStore.snapshot(group.id)
        if (selection.isManual && selection.profileId in group.cloudflareMemberIds) return
        val latencies =
            group.members.filterNot { it.id in group.cloudflareMemberIds }.map { member ->
                MemberLatency(
                    profileId = member.id,
                    latencyMillis = probe.measure(member, MemberProbeTarget.Reachability(probeUrl)),
                )
            }
        currentCoroutineContext().ensureActive()
        if (scopeProvider.capture(group) == scope) {
            currentCoroutineContext().ensureActive()
            // Keep the last active exit when no direct candidate is reachable. A prior automatic
            // Cloudflare choice is displaced as soon as a direct candidate recovers, never re-promoted.
            val current = selection.profileId
            val winner = bestSwitchCandidate(latencies, current, toleranceMs)
            if (winner != null && winner != current &&
                selectionStore.selectAutomatically(group.id, selection, winner)
            ) {
                log.i { "urltest selecting lower-latency member $winner for group ${group.id}" }
            }
        }
    }

    private suspend fun runPayloadPass(
        group: ProxyGroup,
        policy: PayloadFailoverPolicy,
    ) {
        val scoped = payloadState(group, policy) ?: return
        if (monotonicMillis() >= scoped.state.nextProbeAt) {
            val results =
                withTimeoutOrNull(payloadPassDeadlineMillis(policy)) {
                    measurePayloadRound(group, scoped, policy)
                }
            if (results == null) {
                // A pass deadline provides no complete observation; rotate without inventing failures.
                scoped.recoveringReserve = null
                scoped.reserveIndex++
                scoped.state.discardObservation()
            } else {
                commitPayloadObservation(group, scoped, results)
            }
        }
    }

    private suspend fun payloadState(
        group: ProxyGroup,
        policy: PayloadFailoverPolicy,
    ): ScopedPayloadState? {
        val scope = scopeProvider.capture(group)
        val selection = selectionStore.snapshot(group.id)
        return if (scope == null || (selection.isManual && selection.profileId in group.cloudflareMemberIds)) {
            payloadStates.remove(group.id)
            null
        } else {
            val previous = payloadStates[group.id]?.takeIf { it.scope == scope && it.selection == selection }
            previous ?: ScopedPayloadState(scope, selection, PayloadFailoverState(policy)).also {
                payloadStates[group.id] = it
            }
        }
    }

    private suspend fun commitPayloadObservation(
        group: ProxyGroup,
        scoped: ScopedPayloadState,
        results: List<MemberLatency>,
    ) {
        currentCoroutineContext().ensureActive()
        val selection = scoped.selection
        if (scopeProvider.capture(group) != scoped.scope || selectionStore.snapshot(group.id) != selection ||
            payloadStates[group.id] !== scoped
        ) {
            payloadStates.remove(group.id, scoped)
        } else {
            currentCoroutineContext().ensureActive()
            val now = monotonicMillis()
            val eligibleReserves =
                group.members.count {
                    it.id !in group.cloudflareMemberIds && it.id != selection.profileId
                }
            val winner =
                scoped.state.observe(
                    results,
                    selection.profileId,
                    now,
                    allCandidatesObserved = scoped.failedReserves.size >= eligibleReserves,
                )
            if (winner != null && selectionStore.selectAutomatically(group.id, selection, winner)) {
                scoped.state.switched(now)
                scoped.recoveringReserve = null
                scoped.failedReserves.clear()
                val selected = selectionStore.snapshot(group.id)
                if (selected.profileId == winner && !selected.isManual) {
                    scoped.selection = selected
                } else {
                    payloadStates.remove(group.id, scoped)
                }
                log.i { "payload-qualified selector failover for group ${group.id}" }
            }
        }
    }

    /** At most the active exit and one reserve are measured, independent of subscription size. */
    private suspend fun measurePayloadRound(
        group: ProxyGroup,
        scoped: ScopedPayloadState,
        policy: PayloadFailoverPolicy,
    ): List<MemberLatency> {
        val eligible = group.members.filterNot { it.id in group.cloudflareMemberIds }
        val active = eligible.firstOrNull { it.id == scoped.selection.profileId }
        val target = MemberProbeTarget.Payload(policy)
        val activeResult = active?.let { MemberLatency(it.id, probe.measure(it, target)) }
        if (activeResult?.latencyMillis != null) {
            scoped.recoveringReserve = null
            scoped.failedReserves.clear()
            return listOf(activeResult)
        }
        val results = listOfNotNull(activeResult)
        val reserves = eligible.filterNot { it.id == active?.id }
        val reserve =
            reserves.takeIf { it.isNotEmpty() }?.let { candidates ->
                candidates.firstOrNull { it.id == scoped.recoveringReserve }
                    ?: candidates[scoped.reserveIndex.mod(candidates.size)]
            }
        return if (reserve == null) {
            results
        } else {
            val result = MemberLatency(reserve.id, probe.measure(reserve, target))
            if (result.latencyMillis != null) {
                scoped.recoveringReserve = reserve.id
                scoped.failedReserves.clear()
            } else {
                scoped.failedReserves.add(reserve.id)
                scoped.recoveringReserve = null
                scoped.reserveIndex = (reserves.indexOf(reserve) + 1).mod(reserves.size)
            }
            results + result
        }
    }

    private fun payloadPassDeadlineMillis(policy: PayloadFailoverPolicy): Long =
        MaxPayloadCandidatesPerPass *
            (policy.probes.size * policy.timeoutSeconds * MillisecondsPerSecond + CandidateLifecycleBudgetMillis)

    private companion object {
        const val MinIntervalSeconds = 1
        const val MinMembers = 2
        const val MaxPayloadCandidatesPerPass = 2
        const val MillisecondsPerSecond = 1_000L
        const val NanosecondsPerMillisecond = 1_000_000L

        // Each candidate reserves 5 seconds for readiness and 5 seconds for native cleanup.
        const val CandidateLifecycleBudgetMillis = 10_000L
    }
}

/**
 * Pure failover decision: the [MemberLatency] to switch to, or `null` when no
 * switch is warranted.
 *
 * - Only reachable members (non-`null` latency) are candidates.
 * - The lowest-latency reachable member wins.
 * - When a [currentProfileId] is set and reachable, the winner must beat it by
 *   **more than** [toleranceMs] — a candidate merely within the tolerance band
 *   does not justify churning the live exit.
 * - When the current selection is unset or unreachable, the lowest-latency
 *   reachable member is chosen outright (any working exit beats none).
 */
fun bestSwitchCandidate(
    latencies: List<MemberLatency>,
    currentProfileId: String?,
    toleranceMs: Int,
): String? {
    // Pair each reachable member with its measured (non-null) latency so the rest
    // of the decision needs no non-null assertions.
    val reachable = latencies.mapNotNull { entry -> entry.latencyMillis?.let { entry.profileId to it } }
    val best = reachable.minByOrNull { (_, latency) -> latency } ?: return null
    val (bestId, bestLatency) = best
    val currentLatency =
        currentProfileId
            ?.let { id -> reachable.firstOrNull { (profileId, _) -> profileId == id }?.second }
    return when {
        // Current selection is unset or unreachable — take the best working member.
        currentLatency == null -> bestId

        // Best must beat the current by strictly more than the tolerance band.
        bestLatency + toleranceMs < currentLatency -> bestId

        else -> null
    }
}
