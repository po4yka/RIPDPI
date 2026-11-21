package com.poyka.ripdpi.subscription

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.PayloadFailoverPolicy
import com.poyka.ripdpi.data.PayloadProbePurpose
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
import com.poyka.ripdpi.data.subscription.EndpointHealthOrder
import com.poyka.ripdpi.data.subscription.orderByEndpointHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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
    private val reserveBudget: (String, PayloadFailoverPolicy, PayloadProbePurpose) -> ProfileQualityBudget,
    /** Wall clock for bundle health expiry (`observed_at + ttl_seconds` is an absolute instant). */
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val measureVpnPayload: suspend (
        SelectorVpnPath,
        PayloadFailoverPolicy,
    ) -> MemberProbeResult? = { _, _ -> null },
) {
    private val log = Logger.withTag("selector-urltest")
    private val payloadStates = ConcurrentHashMap<String, ScopedPayloadState>()
    private val probeRoundMutex = Mutex()
    private var nextRecoveryAt = Long.MIN_VALUE

    /** Consecutive reachability passes in which the selected member, keyed by group, failed its check. */
    private val unreachablePasses = ConcurrentHashMap<String, Pair<String, Int>>()

    private data class VpnRecovery(
        val previous: AppliedRuntimeConfiguration,
        val promotion: ProfileQualityDecision?,
        var applied: Boolean = false,
    ) {
        fun matches(
            path: SelectorVpnPath?,
            groupId: String,
            memberId: String?,
        ): Boolean = path?.configuration?.isAppliedSelectorSuccessor(previous, groupId, memberId) == true
    }

    private inner class ScopedPayloadState(
        var scope: SelectorProbeScope,
        var selection: SelectorSelectionSnapshot,
        val state: PayloadFailoverState,
        val quality: PayloadQualityEvidence = PayloadQualityEvidence(),
        var qualityGeneration: Long = 0,
        var reserveIndex: Int = 0,
        var tcpPriority: Boolean = false,
        var recoveringReserve: String? = null,
        val failedReserves: MutableSet<String> = mutableSetOf(),
        var routeUnavailable: Boolean = false,
        var vpnRecovery: VpnRecovery? = null,
    ) {
        fun activeTransferFailed(results: List<MemberLatency>): Boolean =
            scope.vpnPath?.usesIsolatedProbes == false &&
                results.any {
                    it.profileId == selection.profileId && it.latencyMillis == null &&
                        it.reason == ProfileQualityReason.TransferFailed
                }

        fun observeVpnApplication(
            groupId: String,
            now: Long,
            isCurrent: () -> Boolean,
        ): VpnRecovery? =
            vpnRecovery?.takeIf { it.matches(scope.vpnPath, groupId, selection.profileId) && isCurrent() }?.also {
                if (!it.applied) {
                    state.switched(now)
                    it.applied = true
                }
            }

        fun canMeasureAppliedPath(recovery: Boolean): Boolean {
            val path = scope.vpnPath ?: return true
            val isolatedScheduledPass = path.usesIsolatedProbes && !recovery && vpnRecovery == null
            val appliedMember = path.configuration.effectiveSelection.selectorMemberId == selection.profileId
            return isolatedScheduledPass || (appliedMember && (path.usesIsolatedProbes || path.lease != null))
        }

        suspend fun commitResults(
            group: ProxyGroup,
            results: List<MemberLatency>,
            recoveryPass: Boolean,
        ): Boolean {
            val now = monotonicMillis()
            val recovery = observeVpnApplication(group.id, now) { scopeProvider.isCurrent(scope) }
            if (results.any { it.reason in UndecidedReasons }) {
                state.discardObservation()
                quality.discard()
                publishQuality(group, this, results, now)
                return false
            }
            val eligibleReserves = probeCandidates(group, scope).count { it.id != selection.profileId }
            val healthWinner =
                state.observe(
                    results,
                    selection.profileId,
                    now,
                    allCandidatesObserved = failedReserves.size >= eligibleReserves,
                    recoveryPass = recoveryPass,
                )
            val activeFailed = activeTransferFailed(results)
            if (recoveryPass && activeFailed) quality.discard()
            val qualityDecision = if (recoveryPass) null else observeQuality(group, this, results, now)
            recordVerifiedRecovery(group.id, recovery, results, now)
            val switched =
                selectWinner(
                    group.id,
                    healthWinner ?: qualityDecision?.winner,
                    qualityDecision?.takeIf { healthWinner == null },
                    now,
                )
            val needsFollowUp = !switched && !recoveryPass && activeFailed
            return needsFollowUp && scopeProvider.isCurrent(scope)
        }

        private fun recordVerifiedRecovery(
            groupId: String,
            recovery: VpnRecovery?,
            results: List<MemberLatency>,
            now: Long,
        ) {
            if (recovery == null || scope.vpnPath?.usesIsolatedProbes != false) return
            val liveVerified = results.any { it.profileId == selection.profileId && it.latencyMillis != null }
            if (liveVerified && scopeProvider.isCurrent(scope) && matchesPayloadGeneration(groupId, this)) {
                vpnRecovery = null
                recovery.promotion?.let {
                    qualityStore.decide(
                        it.copy(observedAtElapsedMillis = now),
                        qualityGeneration,
                    )
                }
                log.i { "Selector VPN traffic recovered on the applied reserve" }
            }
        }

        suspend fun selectWinner(
            groupId: String,
            winner: String?,
            qualityDecision: QualityDecision?,
            now: Long,
        ): Boolean {
            if (winner == null ||
                !selectionStore.selectAutomatically(groupId, selection, winner) { scopeProvider.isCurrent(scope) }
            ) {
                return false
            }
            val promotion =
                qualityDecision?.let {
                    ProfileQualityDecision(groupId, winner, it.challengerId, it.reason, now)
                }
            recordSwitchRequest(promotion, now)?.let { qualityStore.decide(it, qualityGeneration) }
            val selected = selectionStore.snapshot(groupId)
            if (selected.profileId == winner && !selected.isManual) {
                selection = selected
            } else {
                payloadStates.remove(groupId, this)
            }
            log.i { "Payload-qualified selector requested a reserve for group $groupId" }
            return true
        }

        fun recordSwitchRequest(
            promotion: ProfileQualityDecision?,
            now: Long,
        ): ProfileQualityDecision? {
            quality.discard()
            recoveringReserve = null
            failedReserves.clear()
            val vpn = scope.vpnPath
            return if (vpn == null) {
                state.switched(now)
                promotion
            } else {
                state.discardObservation()
                vpnRecovery = VpnRecovery(vpn.configuration, promotion)
                null
            }
        }

        /** Measures one reserve, preferring the one that is recovering, and advances the reserve rotation. */
        suspend fun measureReserve(
            candidates: List<ProxyProfile>,
            tcpPriority: Boolean,
            measure: suspend (ProxyProfile) -> MemberLatency,
        ): MemberLatency? {
            val reserves = if (tcpPriority) candidates.preferTcpReserves() else candidates
            val reserve =
                reserves.takeIf { it.isNotEmpty() }?.let { members ->
                    members.firstOrNull { it.id == recoveringReserve } ?: members[reserveIndex.mod(members.size)]
                }
            return reserve?.let {
                val result = measure(it)
                if (result.latencyMillis != null) {
                    recoveringReserve = it.id
                    failedReserves.clear()
                } else {
                    failedReserves.add(it.id)
                    recoveringReserve = null
                    reserveIndex = (reserves.indexOf(it) + 1).mod(reserves.size)
                }
                result
            }
        }
    }

    /** Fences a replaced or stopped policy against a probe already about to commit. */
    internal fun invalidatePendingSelection(groupId: String) {
        payloadStates.remove(groupId)
        unreachablePasses.remove(groupId)
        qualityStore.invalidate(groupId)
        selectionStore.invalidatePendingSelection(groupId)
    }

    /**
     * Runs the probe loop for [group] until the surrounding coroutine is
     * cancelled. A no-op (returns immediately) when the group has no urltest
     * [com.poyka.ripdpi.data.SelectorFailover] or fewer than two members — there
     * is nothing to fail over between.
     */
    @OptIn(FlowPreview::class)
    suspend fun run(
        group: ProxyGroup,
        recoveryRequests: Flow<Unit> = emptyFlow(),
    ) {
        val failover = group.failover ?: return
        if (group.members.size < MinMembers) return
        val intervalMillis =
            failover.intervalSeconds
                .coerceAtLeast(MinIntervalSeconds)
                .seconds.inWholeMilliseconds
        coroutineScope {
            val activeFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val recoveryWatcher =
                failover.payloadPolicy?.let { policy ->
                    launch {
                        val latestRequest = AtomicLong()
                        merge(recoveryRequests, activeFailures)
                            .map { latestRequest.incrementAndGet() }
                            .debounce(RecoveryQuietMillis)
                            .conflate()
                            .collect { request ->
                                contained("recovery pass") {
                                    runPayloadPass(group, policy, PayloadProbePurpose.Recovery) {
                                        latestRequest.get() ==
                                            request
                                    }
                                }
                            }
                    }
                }
            val selectionWatcher =
                launch {
                    selectionStore.selectedProfileId(group.id).collect {
                        contained("selection watch") { invalidateChangedQualityScope(group) }
                    }
                }
            try {
                // The first pass follows session start closely; later passes keep the policy interval.
                var wait = FirstPassDelayMillis.coerceAtMost(intervalMillis)
                while (true) {
                    delay(wait)
                    contained("probe pass") {
                        if (runProbePass(group, failover.probeUrl, failover.toleranceMs)) activeFailures.tryEmit(Unit)
                    }
                    wait = intervalMillis
                }
            } finally {
                recoveryWatcher?.cancel()
                selectionWatcher.cancel()
                invalidatePendingSelection(group.id)
            }
        }
    }

    /**
     * Members that may win this pass. Cloudflare members never win automatically, so health is ranked over the
     * direct members only: when all of them are down they fail open even while a Cloudflare member is up. Bundle
     * health is evaluated now, so expired down health is neutral again.
     */
    private fun probeCandidates(
        group: ProxyGroup,
        scope: SelectorProbeScope,
    ): List<ProxyProfile> =
        when (
            val order =
                orderByEndpointHealth(
                    group.members.filterNot { it.id in group.cloudflareMemberIds },
                    // Health refreshes with every bundle refresh and is read per pass, not from the loop's start.
                    scope.memberHealth,
                    wallClockMillis(),
                )
        ) {
            is EndpointHealthOrder.Ranked -> {
                order.members
            }

            is EndpointHealthOrder.AllDownFailOpen -> {
                log.i { "urltest group ${group.id}: every member reported down; health ignored" }
                order.members
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
        if (manualChanged || !scoped.scope.sameProbe(scopeProvider.capture(group))) {
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
    ): Boolean {
        val policy = group.failover?.payloadPolicy
        return if (policy != null) {
            runPayloadPass(group, policy, PayloadProbePurpose.Scheduled)
        } else {
            runReachabilityPass(group, probeUrl, toleranceMs)
            false
        }
    }

    private suspend fun runReachabilityPass(
        group: ProxyGroup,
        probeUrl: String,
        toleranceMs: Int,
    ) {
        val scope = scopeProvider.capture(group) ?: return
        val selection = selectionStore.snapshot(group.id)
        // A manual pick pins its member; the store refuses an automatic move anyway, so skip the probes.
        if (selection.isManual) return
        val target = MemberProbeTarget.Reachability(probeUrl, group.id.takeIf { group.fieldReport != null })
        val latencies =
            probeCandidates(group, scope).map { member ->
                val result = probeRoundMutex.withLock { probe.measure(member, target) }
                MemberLatency(
                    member.id,
                    result?.latencyMillis,
                    reason = result?.reason ?: ProfileQualityReason.TransferFailed,
                )
            }
        currentCoroutineContext().ensureActive()
        if (scope.sameProbe(scopeProvider.capture(group))) {
            currentCoroutineContext().ensureActive()
            applyReachabilityPass(group, selection, latencies, toleranceMs)
        }
    }

    private suspend fun applyReachabilityPass(
        group: ProxyGroup,
        selection: SelectorSelectionSnapshot,
        latencies: List<MemberLatency>,
        toleranceMs: Int,
    ) {
        // Keep the last active exit when no direct candidate is reachable. A prior automatic
        // Cloudflare choice is displaced as soon as a direct candidate recovers, never re-promoted.
        val current = selection.profileId
        val currentResult = latencies.firstOrNull { it.profileId == current }
        // A check that could not judge the current member (cleanup, busy, network change) decides nothing.
        if (currentResult?.reason in UndecidedReasons) return
        val failed = currentResult?.takeIf { it.latencyMillis == null }
        val failures =
            failed?.let { (unreachablePasses[group.id]?.takeIf { p -> p.first == it.profileId }?.second ?: 0) + 1 }
                ?: 0
        if (failed == null) {
            unreachablePasses.remove(group.id)
        } else {
            unreachablePasses[group.id] = failed.profileId to failures
        }
        // One lost pass on a lossy path is not an outage: switch for unreachability only after consecutive ones.
        if (failures in 1 until UnreachablePassesBeforeSwitch) return
        val winner = bestSwitchCandidate(latencies, current, toleranceMs)
        // Reachability probes use isolated candidates, not the applied VPN route, so no route authority applies.
        if (winner != null && winner != current &&
            selectionStore.selectAutomatically(group.id, selection, winner) { true }
        ) {
            log.i { "urltest selecting lower-latency member $winner for group ${group.id}" }
        }
    }

    private suspend fun runPayloadPass(
        group: ProxyGroup,
        policy: PayloadFailoverPolicy,
        purpose: PayloadProbePurpose,
        requestIsQuiet: () -> Boolean = { true },
    ): Boolean =
        probeRoundMutex.withLock {
            // Wait for other selector work before capturing scope or starting the measurement budget.
            val scoped = payloadState(group, policy) ?: return@withLock false
            val now = monotonicMillis()
            val recovery = purpose == PayloadProbePurpose.Recovery
            val due =
                if (recovery) {
                    now >= nextRecoveryAt && !scoped.state.inOutageBackoff(now)
                } else {
                    now >=
                        scoped.state.nextProbeAt
                }
            if (!due) return@withLock false
            if (!scoped.canMeasureAppliedPath(recovery) || !requestIsQuiet()) return@withLock false
            if (recovery && !scopeProvider.isCurrent(scoped.scope)) return@withLock false
            try {
                if (!admitPayloadPass(group, scoped, policy, purpose)) return@withLock false
                val probePolicy = if (recovery) policy.copy(quality = null) else policy
                var routeUnavailable = false
                val results =
                    withTimeoutOrNull(payloadPassDeadlineMillis(probePolicy)) {
                        measurePayloadRound(group, scoped, probePolicy).also { routeUnavailable = it == null }
                    }
                if (routeUnavailable) {
                    // The applied VPN route could not be measured: this pass observed nothing, so earlier
                    // evidence (failure streak, reserve progress, cooldown) stays as it was.
                    if (!scoped.routeUnavailable) {
                        log.w { "Payload pass for group ${group.id} skipped: applied VPN route unavailable" }
                    }
                    scoped.routeUnavailable = true
                } else if (results == null) {
                    // A pass deadline provides no complete observation; rotate without inventing failures.
                    scoped.recoveringReserve = null
                    scoped.reserveIndex++
                    scoped.state.discardObservation()
                    scoped.quality.discard()
                } else {
                    scoped.routeUnavailable = false
                    return@withLock commitPayloadObservation(group, scoped, results, recovery)
                }
                false
            } finally {
                if (recovery) nextRecoveryAt = monotonicMillis() + RecoverySpacingMillis
            }
        }

    private suspend fun payloadState(
        group: ProxyGroup,
        policy: PayloadFailoverPolicy,
    ): ScopedPayloadState? {
        val scope = scopeProvider.capture(group)
        val selection = selectionStore.snapshot(group.id)
        return if (scope == null || selection.isManual) {
            payloadStates.remove(group.id)
            qualityStore.invalidate(group.id)
            null
        } else {
            val previous =
                payloadStates[group.id]
                    ?.takeIf {
                        it.scope.sameProbe(scope) && it.selection == selection &&
                            it.qualityGeneration == qualityStore.generation(group.id)
                    }?.also { it.scope = scope }
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
        recoveryPass: Boolean,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        val latestScope = scopeProvider.capture(group)
        val scopeMatches = matchesCurrentScope(group, scoped, latestScope)
        if (!scopeMatches) {
            payloadStates.remove(group.id, scoped)
            qualityStore.invalidate(group.id)
        }
        currentCoroutineContext().ensureActive()
        val sameRoute = scoped.scope.vpnPath == latestScope?.vpnPath
        val usable = scopeMatches && sameRoute && scopeProvider.isCurrent(scoped.scope)
        return usable && scoped.commitResults(group, results, recoveryPass)
    }

    private suspend fun matchesCurrentScope(
        group: ProxyGroup,
        scoped: ScopedPayloadState,
        latestScope: SelectorProbeScope?,
    ): Boolean =
        scoped.scope.sameProbe(latestScope) && selectionStore.snapshot(group.id) == scoped.selection &&
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
    ): List<MemberLatency>? {
        // Returns null when the applied VPN route could not be measured; such a pass decides nothing.
        val path = scoped.scope.vpnPath
        if (path?.usesIsolatedProbes == true && scoped.vpnRecovery != null &&
            path.configuration.effectiveSelection.selectorMemberId != scoped.selection.profileId
        ) {
            return null
        }
        val eligible = probeCandidates(group, scoped.scope)
        val vpnPath = path?.takeUnless { it.usesIsolatedProbes }
        val active =
            (if (vpnPath == null) eligible else scoped.scope.members).firstOrNull {
                it.id == scoped.selection.profileId
            }
        val target = MemberProbeTarget.Payload(policy, group.id.takeIf { group.fieldReport != null })
        val activeResult =
            if (vpnPath == null) {
                active?.let { measureMember(it, target) }
            } else {
                measureActiveVpnMember(vpnPath, active, policy, measureVpnPayload)
            }
        val routeUnmeasured =
            vpnPath != null && (activeResult == null || activeResult.reason == ProfileQualityReason.ScopeChanged)
        val tcpPriority = active is ProxyProfile.Hysteria2 && activeResult?.latencyMillis == null
        if (!routeUnmeasured && scoped.tcpPriority != tcpPriority) {
            scoped.tcpPriority = tcpPriority
            scoped.reserveIndex = 0
            scoped.recoveringReserve = null
            scoped.failedReserves.clear()
        }
        // A verified recovery starts with the live exit alone. Challenger cleanup or a slow comparison cannot hide it.
        val confirmRecovery = vpnPath != null && scoped.vpnRecovery?.matches(vpnPath, group.id, active?.id) == true
        return when {
            routeUnmeasured -> {
                null
            }

            activeResult?.latencyMillis != null && (policy.quality == null || confirmRecovery) -> {
                scoped.recoveringReserve = null
                scoped.failedReserves.clear()
                listOf(activeResult)
            }

            else -> {
                val reserves = eligible.filterNot { it.id == active?.id }
                listOfNotNull(activeResult) +
                    listOfNotNull(scoped.measureReserve(reserves, tcpPriority) { measureMember(it, target) })
            }
        }
    }

    private fun admitPayloadPass(
        group: ProxyGroup,
        scoped: ScopedPayloadState,
        policy: PayloadFailoverPolicy,
        purpose: PayloadProbePurpose,
    ): Boolean {
        val budget = reserveBudget(group.id, policy, purpose)
        qualityStore.budget(budget)
        if (budget.reason == ProfileQualityReason.Verified) return true
        if (purpose == PayloadProbePurpose.Scheduled) {
            scoped.quality.discard()
        }
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
        const val UnreachablePassesBeforeSwitch = 2
        const val FirstPassDelayMillis = 15_000L
        const val RecoveryQuietMillis = 5_000L
        const val RecoverySpacingMillis = 30_000L
        val UndecidedReasons = setOf(ProfileQualityReason.CleanupPending, ProfileQualityReason.ScopeChanged)
        const val MaxPayloadCandidatesPerPass = 2
        const val MillisecondsPerSecond = 1_000L
        const val NanosecondsPerMillisecond = 1_000_000L

        // Each candidate reserves 5 seconds for readiness and 5 seconds for native cleanup.
        const val CandidateLifecycleBudgetMillis = 10_000L
    }
}

private suspend fun measureActiveVpnMember(
    path: SelectorVpnPath,
    member: ProxyProfile?,
    policy: PayloadFailoverPolicy,
    measure: suspend (SelectorVpnPath, PayloadFailoverPolicy) -> MemberProbeResult?,
): MemberLatency? {
    val appliedMemberId = path.configuration.effectiveSelection.selectorMemberId ?: return null
    val result = member?.takeIf { it.id == appliedMemberId }?.let { measure(path, policy) }
    return MemberLatency(
        appliedMemberId,
        result?.latencyMillis,
        result?.quality,
        result?.reason ?: ProfileQualityReason.ScopeChanged,
    )
}
