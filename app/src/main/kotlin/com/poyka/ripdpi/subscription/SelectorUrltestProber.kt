package com.poyka.ripdpi.subscription

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/** A measured latency for a selector member, or `null` when the member is unreachable. */
data class MemberLatency(
    val profileId: String,
    val latencyMillis: Long?,
)

/**
 * Measures a single selector member's reachability latency against the group's
 * `probeUrl`. Injectable so the prober is testable without a real network: the
 * production impl measures TCP connect only; this does not prove payload health.
 */
fun interface MemberLatencyProbe {
    /**
     * Returns the round-trip latency in milliseconds for [profile] probed against
     * [probeUrl], or `null` when the member did not respond.
     */
    suspend fun measure(
        profile: ProxyProfile,
        probeUrl: String,
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
class SelectorUrltestProber(
    private val selectionStore: SelectorSelectionStore,
    private val probe: MemberLatencyProbe,
) {
    private val log = Logger.withTag("selector-urltest")

    /** Fences a replaced or stopped policy against a probe already about to commit. */
    internal fun invalidatePendingSelection(groupId: String) {
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
        while (true) {
            delay(intervalMillis)
            runProbePass(group, failover.probeUrl, failover.toleranceMs)
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
        val selection = selectionStore.snapshot(group.id)
        if (selection.isManual && selection.profileId in group.cloudflareMemberIds) return
        val latencies =
            group.members.filterNot { it.id in group.cloudflareMemberIds }.map { member ->
                MemberLatency(profileId = member.id, latencyMillis = probe.measure(member, probeUrl))
            }
        currentCoroutineContext().ensureActive()
        // Keep the last active exit when no direct candidate is reachable. A prior automatic
        // Cloudflare choice is displaced as soon as a direct candidate recovers, never re-promoted.
        val current = selection.profileId
        val winner = bestSwitchCandidate(latencies, current, toleranceMs)
        if (winner != null && winner != current && selectionStore.selectAutomatically(group.id, selection, winner)) {
            log.i { "urltest selecting lower-latency member $winner for group ${group.id}" }
        }
    }

    private companion object {
        const val MinIntervalSeconds = 1
        const val MinMembers = 2
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
