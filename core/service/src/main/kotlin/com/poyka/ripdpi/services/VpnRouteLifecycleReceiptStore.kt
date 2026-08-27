package com.poyka.ripdpi.services

import android.os.Build
import com.poyka.ripdpi.data.VpnRouteAppRoutingShape
import com.poyka.ripdpi.data.VpnRouteCallbackState
import com.poyka.ripdpi.data.VpnRouteConsistency
import com.poyka.ripdpi.data.VpnRouteEvidence
import com.poyka.ripdpi.data.VpnRouteEvidenceProvider
import com.poyka.ripdpi.data.VpnRouteFamilyIpv4
import com.poyka.ripdpi.data.VpnRouteFamilyIpv6
import com.poyka.ripdpi.data.VpnRouteLifecycleReceipt
import com.poyka.ripdpi.data.VpnRouteLifecycleState
import com.poyka.ripdpi.data.VpnRouteObservationConvergenceMillis
import com.poyka.ripdpi.data.VpnRouteOwnerVerification
import com.poyka.ripdpi.data.boundedNetworkPathCount
import com.poyka.ripdpi.data.networkPathMtuBand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnRouteLifecycleReceiptStore
    internal constructor(
        private val nanoTime: () -> Long,
    ) : VpnRouteEvidenceProvider {
        @Inject
        constructor() : this(System::nanoTime)

        private val nextGeneration = AtomicLong()
        private val changeCounter = MutableStateFlow(0L)
        private val publishedEvidence = AtomicReference(VpnRouteEvidence())

        @Volatile
        private var publishedAtNanos = 0L
        private val pendingReceipts = linkedMapOf<Long, ReceiptRecord>()
        private val callbackReducer = VpnRouteCallbackReducer()
        private var activeReceipt: ReceiptRecord? = null

        override val changes: StateFlow<Long> = changeCounter.asStateFlow()

        @Synchronized
        fun beginIntended(
            ipv6Enabled: Boolean,
            dns: String,
            appRoutingPlan: VpnAppRoutingPlan,
            ownPackage: String?,
            networkParameters: VpnTunnelNetworkParameters,
            apiLevel: Int,
        ): Long {
            val generation = nextGeneration.incrementAndGet()
            val routeFamilies = intendedDefaultRouteFamilies(ipv6Enabled)
            pendingReceipts[generation] =
                ReceiptRecord(
                    receipt =
                        VpnRouteLifecycleReceipt(
                            generation = generation,
                            state = VpnRouteLifecycleState.Intended,
                            intendedDefaultRouteFamilies = routeFamilies,
                            addressFamilies = routeFamilies,
                            dnsServerFamilies = dnsAddressFamilies(dns),
                            appRoutingShape = appRoutingPlan.shape(),
                            configuredAppCount = boundedNetworkPathCount(appRoutingPlan.configuredAppCount()),
                            ownPackageExcluded = appRoutingPlan.excludesOwnPackage(ownPackage),
                            ipv6Intent = ipv6Enabled,
                            mtuBand = networkPathMtuBand(networkParameters.tunnelMtu),
                            metered = networkParameters.metered.takeIf { apiLevel >= Build.VERSION_CODES.Q },
                        ),
                    callbackAnchor = callbackReducer.generationAnchor(),
                )
            publishCurrent()
            return generation
        }

        @Synchronized
        fun markEstablished(generation: Long) {
            val intended = pendingReceipts.remove(generation) ?: return
            activeReceipt =
                intended
                    .withState(VpnRouteLifecycleState.Established)
                    .copy(convergenceStartedAtNanos = nanoTime())
            publishCurrent()
        }

        @Synchronized
        fun abortIntended(generation: Long) {
            if (pendingReceipts.remove(generation) != null) publishCurrent()
        }

        @Synchronized
        fun markBridgeReady(
            generation: Long,
            appliedTunnelReceiptGeneration: Long? = null,
        ) {
            val current = activeReceipt?.takeIf { it.receipt.generation == generation } ?: return
            activeReceipt =
                current.copy(
                    receipt =
                        current.receipt.copy(
                            state = VpnRouteLifecycleState.BridgeReady,
                            appliedTunnelReceiptGeneration = appliedTunnelReceiptGeneration,
                        ),
                )
            publishCurrent(refreshRouteEvidenceAge = false)
        }

        @Synchronized
        fun markEnded(
            generation: Long,
            state: VpnRouteLifecycleState,
        ) {
            require(state == VpnRouteLifecycleState.FailClosed || state == VpnRouteLifecycleState.Closed)
            updateActive(
                generation = generation,
                state = state,
                forwardingOutcome = "fail_closed".takeIf { state == VpnRouteLifecycleState.FailClosed },
                forwardingTerminal = true.takeIf { state == VpnRouteLifecycleState.FailClosed },
            )
        }

        @Synchronized
        fun recordForwardingOutcome(
            generation: Long,
            outcome: String,
            terminal: Boolean,
            revision: Long,
        ) {
            val current =
                activeReceipt?.takeIf {
                    it.receipt.generation == generation &&
                        it.receipt.state in liveLifecycleStates &&
                        it.receipt.state != VpnRouteLifecycleState.FailClosed
                } ?: return
            val safeOutcome = outcome.takeIf(AllowedForwardingOutcomes::contains) ?: "unavailable"
            val staleRevision = revision <= current.forwardingRevision
            val unchanged = current.forwardingOutcome == safeOutcome && current.forwardingTerminal == terminal
            if (!staleRevision && unchanged) {
                activeReceipt = current.copy(forwardingRevision = revision)
            } else if (!staleRevision) {
                activeReceipt =
                    current.copy(
                        forwardingOutcome = safeOutcome,
                        forwardingTerminal = terminal,
                        forwardingRevision = revision,
                    )
                publishCurrent(refreshRouteEvidenceAge = false)
            }
        }

        @get:Synchronized
        val lifecycleReceipt: VpnRouteLifecycleReceipt?
            get() = currentRecord()?.receipt

        @Synchronized
        fun hasCorrelatableReceipt(): Boolean =
            pendingReceipts.isNotEmpty() || activeReceipt?.receipt?.state in liveLifecycleStates

        @Synchronized
        fun setObserverAvailable(available: Boolean) {
            callbackReducer.setObserverAvailable(available)
            publishCurrent()
        }

        @Synchronized
        fun observeAvailable(networkKey: Any) {
            callbackReducer.observeAvailable(networkKey)
            publishCurrent()
        }

        @Synchronized
        fun observeCapabilities(
            networkKey: Any,
            hasInternet: Boolean,
            validated: Boolean,
            captivePortal: Boolean,
            ownerVerification: VpnRouteOwnerVerification,
        ) {
            callbackReducer.observeCapabilities(
                networkKey = networkKey,
                hasInternet = hasInternet,
                validated = validated,
                captivePortal = captivePortal,
                ownerVerification = ownerVerification,
            )
            publishCurrent()
        }

        @Synchronized
        fun observeDefaultRoutes(
            networkKey: Any,
            families: Set<String>,
        ) {
            callbackReducer.observeDefaultRoutes(networkKey, families)
            publishCurrent()
        }

        @Synchronized
        fun observeLost(networkKey: Any) {
            callbackReducer.observeLost(networkKey)
            publishCurrent()
        }

        @Synchronized
        fun discardObservation(networkKey: Any) {
            callbackReducer.discard(networkKey)
            publishCurrent()
        }

        @Synchronized
        override fun capture(): VpnRouteEvidence {
            val evidence = publishedEvidence.get()
            if (evidence.lifecycle == null || publishedAtNanos == 0L) return evidence
            val ageNanos = (nanoTime() - publishedAtNanos).coerceAtLeast(0L)
            val agedEvidence = evidence.copy(evidenceAgeBand = evidenceAgeBand(ageNanos))
            val convergenceAgeNanos =
                currentRecord()
                    ?.convergenceStartedAtNanos
                    ?.let { startedAt -> (nanoTime() - startedAt).coerceAtLeast(0L) }
            return if (
                agedEvidence.callbackState == VpnRouteCallbackState.Awaiting &&
                convergenceAgeNanos != null &&
                convergenceAgeNanos >= VpnRouteObservationConvergenceMillis * NanosPerMillisecond
            ) {
                agedEvidence.copy(callbackState = VpnRouteCallbackState.TimedOut)
            } else {
                agedEvidence
            }
        }

        @Synchronized
        override fun convergenceRefreshDelayMillis(): Long? {
            val startedAt = currentRecord()?.convergenceStartedAtNanos
            return if (
                publishedEvidence.get().callbackState == VpnRouteCallbackState.Awaiting &&
                startedAt != null
            ) {
                val elapsedNanos = (nanoTime() - startedAt).coerceAtLeast(0L)
                val remainingNanos =
                    (VpnRouteObservationConvergenceMillis * NanosPerMillisecond - elapsedNanos).coerceAtLeast(0L)
                (remainingNanos + NanosPerMillisecond - 1L) / NanosPerMillisecond
            } else {
                null
            }
        }

        private fun updateActive(
            generation: Long,
            state: VpnRouteLifecycleState,
            forwardingOutcome: String? = null,
            forwardingTerminal: Boolean? = null,
        ) {
            val current = activeReceipt?.takeIf { it.receipt.generation == generation } ?: return
            activeReceipt =
                current.withState(state).let { updated ->
                    if (forwardingOutcome == null) {
                        updated
                    } else {
                        updated.copy(
                            forwardingOutcome = forwardingOutcome,
                            forwardingTerminal = forwardingTerminal,
                        )
                    }
                }
            publishCurrent()
        }

        private fun publishCurrent(refreshRouteEvidenceAge: Boolean = true) {
            val record = currentRecord()
            publishedEvidence.set(
                record?.let { callbackReducer.project(it) } ?: VpnRouteEvidence(),
            )
            if (refreshRouteEvidenceAge) publishedAtNanos = nanoTime()
            changeCounter.value += 1L
        }

        private fun currentRecord(): ReceiptRecord? =
            activeReceipt
                ?.takeIf { it.receipt.state in liveLifecycleStates }
                ?: pendingReceipts.values.lastOrNull()
                ?: activeReceipt
    }

private val liveLifecycleStates =
    setOf(
        VpnRouteLifecycleState.Established,
        VpnRouteLifecycleState.BridgeReady,
        VpnRouteLifecycleState.FailClosed,
    )

private data class ReceiptRecord(
    val receipt: VpnRouteLifecycleReceipt,
    val callbackAnchor: CallbackGenerationAnchor,
    val forwardingOutcome: String = "unavailable",
    val forwardingTerminal: Boolean? = null,
    val forwardingRevision: Long = 0L,
    val convergenceStartedAtNanos: Long? = null,
) {
    fun withState(state: VpnRouteLifecycleState): ReceiptRecord {
        val updatedReceipt = receipt.copy(state = state)
        return copy(receipt = updatedReceipt)
    }
}

private class VpnRouteCallbackReducer {
    private val candidates = linkedMapOf<Any, CallbackCandidate>()
    private var eventOrder = 0L
    private var revision = 0L
    private var lastFingerprint: CallbackFingerprint? = null
    private val losses = mutableMapOf<Any, Long>()
    private var observerAvailable = true

    fun generationAnchor(): CallbackGenerationAnchor = CallbackGenerationAnchor(eventOrder, candidates.keys.toSet())

    fun setObserverAvailable(available: Boolean) {
        observerAvailable = available
        if (!available) {
            candidates.clear()
            losses.clear()
        }
    }

    fun observeAvailable(networkKey: Any) {
        observerAvailable = true
        eventOrder += 1L
        losses.remove(networkKey)
        candidates.getOrPut(networkKey, ::CallbackCandidate).availableOrder = eventOrder
    }

    fun observeCapabilities(
        networkKey: Any,
        hasInternet: Boolean,
        validated: Boolean,
        captivePortal: Boolean,
        ownerVerification: VpnRouteOwnerVerification,
    ) {
        observerAvailable = true
        eventOrder += 1L
        losses.remove(networkKey)
        candidates.getOrPut(networkKey, ::CallbackCandidate).capabilities =
            CallbackCapabilities(
                eventOrder = eventOrder,
                hasInternet = hasInternet,
                validated = validated,
                captivePortal = captivePortal,
                ownerVerification = ownerVerification,
            )
    }

    fun observeDefaultRoutes(
        networkKey: Any,
        families: Set<String>,
    ) {
        observerAvailable = true
        eventOrder += 1L
        losses.remove(networkKey)
        candidates.getOrPut(networkKey, ::CallbackCandidate).routes =
            CallbackRoutes(
                eventOrder = eventOrder,
                families = families.filter(::isSupportedRouteFamily).sorted(),
            )
    }

    fun observeLost(networkKey: Any) {
        eventOrder += 1L
        candidates.remove(networkKey)
        losses[networkKey] = eventOrder
    }

    fun discard(networkKey: Any) {
        candidates.remove(networkKey)
    }

    fun project(record: ReceiptRecord): VpnRouteEvidence =
        when {
            record.receipt.state !in liveLifecycleStates -> VpnRouteEvidence(lifecycle = record.receipt)
            !observerAvailable -> VpnRouteEvidence(lifecycle = record.receipt)
            else -> projectLiveEvidence(record.receipt, record.callbackAnchor)
        }.copy(
            forwardingOutcome = record.forwardingOutcome,
            forwardingLifecycleGeneration =
                record.receipt.generation.takeIf { record.forwardingOutcome != "unavailable" },
            forwardingTerminal = record.forwardingTerminal,
        )

    private fun projectLiveEvidence(
        receipt: VpnRouteLifecycleReceipt,
        anchor: CallbackGenerationAnchor,
    ): VpnRouteEvidence {
        val complete = selectCompleteCandidate(anchor)
        return if (complete == null) {
            val state =
                if (hasCurrentGenerationLoss(anchor) && !hasPartialCandidate(anchor)) {
                    VpnRouteCallbackState.Lost
                } else {
                    VpnRouteCallbackState.Awaiting
                }
            VpnRouteEvidence(
                lifecycle = receipt,
                callbackState = state,
                vpnPresent = false.takeIf { state == VpnRouteCallbackState.Lost },
            )
        } else {
            projectCompleteEvidence(receipt, complete)
        }
    }

    private fun hasCurrentGenerationLoss(anchor: CallbackGenerationAnchor): Boolean =
        losses.any { (networkKey, order) ->
            networkKey !in anchor.preexistingNetworkKeys && order > anchor.eventFloor
        }

    private fun hasPartialCandidate(anchor: CallbackGenerationAnchor): Boolean =
        candidates.any { (networkKey, candidate) ->
            networkKey !in anchor.preexistingNetworkKeys && candidate.hasEventAfter(anchor.eventFloor)
        }

    private fun projectCompleteEvidence(
        receipt: VpnRouteLifecycleReceipt,
        complete: CompleteCallbackCandidate,
    ): VpnRouteEvidence {
        val fingerprint = complete.fingerprint(receipt.generation)
        if (fingerprint != lastFingerprint) {
            revision += 1L
            lastFingerprint = fingerprint
        }
        return VpnRouteEvidence(
            lifecycle = receipt,
            callbackState = VpnRouteCallbackState.Complete,
            callbackRevision = revision,
            ownerVerification = complete.capabilities.ownerVerification,
            observedDefaultRouteFamilies = complete.routes.families,
            routeConsistency =
                if (complete.routes.families == receipt.intendedDefaultRouteFamilies) {
                    VpnRouteConsistency.Consistent
                } else {
                    VpnRouteConsistency.Mismatch
                },
            vpnPresent = true,
            hasInternet = complete.capabilities.hasInternet,
            validated = complete.capabilities.validated,
            captivePortal = complete.capabilities.captivePortal,
        )
    }

    private fun selectCompleteCandidate(anchor: CallbackGenerationAnchor): CompleteCallbackCandidate? =
        candidates
            .asSequence()
            .filterNot { (key, _) -> key in anchor.preexistingNetworkKeys }
            .mapNotNull { (key, candidate) -> candidate.complete(key, anchor.eventFloor) }
            .maxByOrNull(CompleteCallbackCandidate::eventOrder)
}

private data class CallbackGenerationAnchor(
    val eventFloor: Long,
    val preexistingNetworkKeys: Set<Any>,
)

private class CallbackCandidate {
    var availableOrder: Long? = null

    // Android updates these axes independently; loss/discard clears the candidate.
    var capabilities: CallbackCapabilities? = null
    var routes: CallbackRoutes? = null

    fun complete(
        networkKey: Any,
        callbackFloor: Long,
    ): CompleteCallbackCandidate? {
        val completeCapabilities = capabilities?.takeIf { it.eventOrder > callbackFloor }
        val completeRoutes = routes?.takeIf { it.eventOrder > callbackFloor }
        return if (completeCapabilities != null && completeRoutes != null) {
            CompleteCallbackCandidate(networkKey, completeCapabilities, completeRoutes)
        } else {
            null
        }
    }

    fun hasEventAfter(callbackFloor: Long): Boolean =
        availableOrder?.let { it > callbackFloor } == true ||
            capabilities?.eventOrder?.let { it > callbackFloor } == true ||
            routes?.eventOrder?.let { it > callbackFloor } == true
}

private data class CallbackCapabilities(
    val eventOrder: Long,
    val hasInternet: Boolean,
    val validated: Boolean,
    val captivePortal: Boolean,
    val ownerVerification: VpnRouteOwnerVerification,
)

private data class CallbackRoutes(
    val eventOrder: Long,
    val families: List<String>,
)

private data class CompleteCallbackCandidate(
    val networkKey: Any,
    val capabilities: CallbackCapabilities,
    val routes: CallbackRoutes,
) {
    fun eventOrder(): Long = maxOf(capabilities.eventOrder, routes.eventOrder)

    fun fingerprint(generation: Long): CallbackFingerprint =
        CallbackFingerprint(
            generation = generation,
            networkKey = networkKey,
            hasInternet = capabilities.hasInternet,
            validated = capabilities.validated,
            captivePortal = capabilities.captivePortal,
            ownerVerification = capabilities.ownerVerification,
            routeFamilies = routes.families,
        )
}

private data class CallbackFingerprint(
    val generation: Long,
    val networkKey: Any,
    val hasInternet: Boolean,
    val validated: Boolean,
    val captivePortal: Boolean,
    val ownerVerification: VpnRouteOwnerVerification,
    val routeFamilies: List<String>,
)

private fun isSupportedRouteFamily(family: String): Boolean =
    family == VpnRouteFamilyIpv4 || family == VpnRouteFamilyIpv6

private fun intendedDefaultRouteFamilies(ipv6Enabled: Boolean): List<String> =
    if (ipv6Enabled) listOf(VpnRouteFamilyIpv4, VpnRouteFamilyIpv6) else listOf(VpnRouteFamilyIpv4)

private fun dnsAddressFamilies(dns: String): List<String> =
    when {
        dns.isBlank() -> emptyList()
        ':' in dns -> listOf(VpnRouteFamilyIpv6)
        dns.count { it == '.' } == Ipv4SeparatorCount -> listOf(VpnRouteFamilyIpv4)
        else -> emptyList()
    }

private const val Ipv4SeparatorCount = 3
private const val NanosPerMillisecond = 1_000_000L
private const val FreshEvidenceNanos = 5_000_000_000L
private const val RecentEvidenceNanos = 60_000_000_000L

private val AllowedForwardingOutcomes =
    setOf(
        "unavailable",
        "fail_closed",
        "evidence_unavailable",
        "evidence_unavailable_partial",
        "no_flow",
        "tun_ingress_no_upstream",
        "upstream_open_no_payload",
        "outbound_only",
        "tun_return_without_proxy_outbound",
        "proxy_outbound_observed",
        "cross_layer_return_observed",
    )

private fun evidenceAgeBand(ageNanos: Long): String =
    when {
        ageNanos <= FreshEvidenceNanos -> "fresh"
        ageNanos <= RecentEvidenceNanos -> "recent"
        else -> "stale"
    }

private fun VpnAppRoutingPlan.shape(): VpnRouteAppRoutingShape =
    when (this) {
        is VpnAppRoutingPlan.AllowOnly -> VpnRouteAppRoutingShape.AllowOnly
        is VpnAppRoutingPlan.Disallow -> VpnRouteAppRoutingShape.Disallow
    }

private fun VpnAppRoutingPlan.configuredAppCount(): Int =
    when (this) {
        is VpnAppRoutingPlan.AllowOnly -> packages.size
        is VpnAppRoutingPlan.Disallow -> packages.size
    }

private fun VpnAppRoutingPlan.excludesOwnPackage(ownPackage: String?): Boolean =
    ownPackage
        ?.takeIf(String::isNotBlank)
        ?.let { packageName ->
            when (this) {
                is VpnAppRoutingPlan.AllowOnly -> packageName !in packages
                is VpnAppRoutingPlan.Disallow -> packageName in packages
            }
        } ?: false
