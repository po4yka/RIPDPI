package com.poyka.ripdpi.subscription

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.PayloadFailoverPolicy
import com.poyka.ripdpi.data.ProfileQualityBudget
import com.poyka.ripdpi.data.ProfileQualityDecision
import com.poyka.ripdpi.data.ProfileQualityDecisionReason
import com.poyka.ripdpi.data.ProfileQualityKey
import com.poyka.ripdpi.data.ProfileQualityObservation
import com.poyka.ripdpi.data.ProfileQualityReason
import com.poyka.ripdpi.data.ProfileQualityStatus
import com.poyka.ripdpi.data.ProfileQualityStore
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.selector.SelectorSelectionSnapshot
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

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
    private val qualityStore: ProfileQualityStore = ProfileQualityStore(),
    private val reserveBudget: (String, PayloadFailoverPolicy) -> ProfileQualityBudget = { id, policy ->
        ProfileQualityBudget(
            id,
            0,
            0,
            requireNotNull(policy.quality).maxWindowBytes,
            null,
            ProfileQualityReason.BudgetUnavailable,
        )
    },
) {
    private val log = Logger.withTag("selector-urltest")
    private val payloadStates = ConcurrentHashMap<String, ScopedPayloadState>()
    private val probeRoundMutex = Mutex()

    private data class ScopedPayloadState(
        val scope: SelectorProbeScope,
        var selection: SelectorSelectionSnapshot,
        val state: PayloadFailoverState,
        val quality: PayloadQualityEvidence = PayloadQualityEvidence(),
        var qualityGeneration: Long = 0,
        var reserveIndex: Int = 0,
        var recoveringReserve: String? = null,
        val failedReserves: MutableSet<String> = mutableSetOf(),
    )

    /** Fences a replaced or stopped policy against a probe already about to commit. */
    internal fun invalidatePendingSelection(groupId: String) {
        payloadStates.remove(groupId)
        qualityStore.invalidate(groupId)
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
        coroutineScope {
            val selectionWatcher =
                launch {
                    selectionStore.selectedProfileId(group.id).collect {
                        contained("selection watch") { invalidateChangedQualityScope(group) }
                    }
                }
            try {
                while (true) {
                    delay(intervalMillis)
                    contained("probe pass") { runProbePass(group, failover.probeUrl, failover.toleranceMs) }
                }
            } finally {
                selectionWatcher.cancel()
                invalidatePendingSelection(group.id)
            }
        }
    }

    // A storage or capture failure (failed prefs commit, Room error) skips this round only. Uncaught,
    // it would reach the handler-less reload scope and kill the app together with its VPN service.
    private suspend fun contained(
        step: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ignored: Exception) {
            log.w { "Urltest $step failed: ${ignored.javaClass.simpleName}" }
        }
    }

    internal suspend fun invalidateChangedQualityScope(group: ProxyGroup) {
        val scoped = payloadStates[group.id] ?: return
        val selection = selectionStore.snapshot(group.id)
        val manualChanged = selection.isManual && selection != scoped.selection
        if (manualChanged || scopeProvider.capture(group) != scoped.scope) {
            if (payloadStates.remove(group.id, scoped)) {
                selectionStore.invalidatePendingSelection(group.id)
                qualityStore.invalidate(group.id)
            }
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
                    latencyMillis =
                        probeRoundMutex.withLock {
                            probe.measure(member, MemberProbeTarget.Reachability(probeUrl))?.latencyMillis
                        },
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
    ) = probeRoundMutex.withLock {
        // Wait for other selector work before capturing scope or starting the measurement budget.
        val scoped = payloadState(group, policy) ?: return@withLock
        if (monotonicMillis() >= scoped.state.nextProbeAt) {
            if (policy.quality != null && !admitQualityPass(group, scoped, policy)) return@withLock
            val results =
                withTimeoutOrNull(payloadPassDeadlineMillis(policy)) {
                    measurePayloadRound(group, scoped, policy)
                }
            if (results == null) {
                // A pass deadline provides no complete observation; rotate without inventing failures.
                scoped.recoveringReserve = null
                scoped.reserveIndex++
                scoped.state.discardObservation()
                scoped.quality.discard()
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
            qualityStore.invalidate(group.id)
            null
        } else {
            val previous =
                payloadStates[group.id]?.takeIf {
                    it.scope == scope && it.selection == selection &&
                        it.qualityGeneration == qualityStore.generation(group.id)
                }
            previous ?: ScopedPayloadState(scope, selection, PayloadFailoverState(policy)).also {
                qualityStore.invalidate(group.id)
                it.qualityGeneration = qualityStore.generation(group.id)
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
        if (!matchesCurrentScope(group, scoped)) {
            payloadStates.remove(group.id, scoped)
            qualityStore.invalidate(group.id)
        } else {
            currentCoroutineContext().ensureActive()
            val now = monotonicMillis()
            val eligibleReserves =
                group.members.count {
                    it.id !in group.cloudflareMemberIds && it.id != selection.profileId
                }
            if (results.any {
                    it.reason == ProfileQualityReason.CleanupPending ||
                        it.reason == ProfileQualityReason.ScopeChanged
                }
            ) {
                scoped.state.discardObservation()
                scoped.quality.discard()
                publishQuality(group, scoped, results, now)
                return
            }
            val healthWinner =
                scoped.state.observe(
                    results,
                    selection.profileId,
                    now,
                    allCandidatesObserved = scoped.failedReserves.size >= eligibleReserves,
                )
            val qualityDecision = observeQuality(group, scoped, results, now)
            val winner = healthWinner ?: qualityDecision?.winner
            if (winner != null && selectionStore.selectAutomatically(group.id, selection, winner)) {
                scoped.state.switched(now)
                scoped.quality.discard()
                if (healthWinner == null && qualityDecision != null) {
                    qualityStore.decide(
                        ProfileQualityDecision(
                            group.id,
                            winner,
                            qualityDecision.challengerId,
                            qualityDecision.reason,
                            now,
                        ),
                        scoped.qualityGeneration,
                    )
                }
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

    private suspend fun matchesCurrentScope(
        group: ProxyGroup,
        scoped: ScopedPayloadState,
    ): Boolean =
        scopeProvider.capture(group) == scoped.scope && selectionStore.snapshot(group.id) == scoped.selection &&
            matchesPayloadGeneration(group.id, scoped)

    private fun matchesPayloadGeneration(
        groupId: String,
        scoped: ScopedPayloadState,
    ): Boolean = payloadStates[groupId] === scoped && scoped.qualityGeneration == qualityStore.generation(groupId)

    private fun observeQuality(
        group: ProxyGroup,
        scoped: ScopedPayloadState,
        results: List<MemberLatency>,
        now: Long,
    ): QualityDecision? {
        val selection = scoped.selection
        val policy = requireNotNull(group.failover?.payloadPolicy)
        val qualityDecision =
            policy.quality?.let {
                scoped.quality.observe(
                    results,
                    selection.profileId,
                    it,
                    policy.recoveryThreshold,
                    scoped.state.inCooldown(now),
                )
            }
        if (qualityDecision != null) {
            publishQuality(group, scoped, results, now)
            val activeHealthy = results.any { it.profileId == selection.profileId && it.latencyMillis != null }
            val reserveHealthy = results.any { it.profileId != selection.profileId && it.latencyMillis != null }
            if (!qualityDecision.qualified && activeHealthy && reserveHealthy) {
                scoped.recoveringReserve = null
                scoped.reserveIndex++
            }
            if (qualityDecision.winner == null) {
                qualityStore.decide(
                    ProfileQualityDecision(
                        group.id,
                        selection.profileId,
                        qualityDecision.challengerId,
                        qualityDecision.reason,
                        now,
                    ),
                    scoped.qualityGeneration,
                )
            }
        }
        return qualityDecision
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
        val activeResult = active?.let { measureMember(it, target) }
        if (activeResult?.latencyMillis != null && policy.quality == null) {
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
            val result = measureMember(reserve, target)
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

    private fun admitQualityPass(
        group: ProxyGroup,
        scoped: ScopedPayloadState,
        policy: PayloadFailoverPolicy,
    ): Boolean {
        val budget = reserveBudget(group.id, policy)
        qualityStore.budget(budget)
        if (budget.reason == ProfileQualityReason.Verified) return true
        scoped.quality.discard()
        scoped.state.discardObservation()
        qualityStore.decide(
            ProfileQualityDecision(
                group.id,
                scoped.selection.profileId,
                null,
                ProfileQualityDecisionReason.DeferredBudget,
                monotonicMillis(),
            ),
            scoped.qualityGeneration,
        )
        return false
    }

    private fun publishQuality(
        group: ProxyGroup,
        scoped: ScopedPayloadState,
        results: List<MemberLatency>,
        now: Long,
    ) {
        qualityStore.observe(
            results.map { result ->
                val measurement = result.quality
                ProfileQualityObservation(
                    key = ProfileQualityKey(group.id, result.profileId),
                    status =
                        when {
                            measurement == null -> ProfileQualityStatus.Unavailable
                            measurement.complete -> ProfileQualityStatus.Verified
                            else -> ProfileQualityStatus.Partial
                        },
                    verifiedBytes = measurement?.verifiedBytes,
                    bodyDurationNanos = measurement?.bodyDurationNanos,
                    goodputBytesPerSecond = measurement?.goodputBytesPerSecond,
                    baselineLatencyMillis = measurement?.baselineLatencyMillis,
                    loadedLatencyMillis = measurement?.loadedLatencyMillis,
                    loadedOverlap = measurement?.loadedOverlap == true,
                    reason = measurement?.reason ?: result.reason,
                    observedAtElapsedMillis = now,
                )
            },
            scoped.qualityGeneration,
        )
    }

    private suspend fun measureMember(
        profile: ProxyProfile,
        target: MemberProbeTarget,
    ): MemberLatency {
        val result = probe.measure(profile, target)
        return MemberLatency(
            profile.id,
            result?.latencyMillis,
            result?.quality,
            result?.reason ?: ProfileQualityReason.TransferFailed,
        )
    }

    private fun payloadPassDeadlineMillis(policy: PayloadFailoverPolicy): Long =
        MaxPayloadCandidatesPerPass *
            (
                policy.probes.size * policy.timeoutSeconds * MillisecondsPerSecond *
                    (if (policy.quality == null) 1 else 2) + CandidateLifecycleBudgetMillis
            )

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
