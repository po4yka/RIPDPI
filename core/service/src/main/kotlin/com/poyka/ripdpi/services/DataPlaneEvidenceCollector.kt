package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ProxyForwardingEvidence
import com.poyka.ripdpi.core.TunForwardingEvidence
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import com.poyka.ripdpi.data.RuntimeTelemetryState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

private const val DataPlaneEventCap = 16

internal sealed interface RuntimeForwardingEvidence<out T> {
    data class Available<T>(
        val value: T,
    ) : RuntimeForwardingEvidence<T>

    data object Unavailable : RuntimeForwardingEvidence<Nothing>
}

internal data class RuntimeTelemetryEvidencePoll<T>(
    val telemetry: RuntimeTelemetryOutcome,
    val forwardingEvidence: RuntimeForwardingEvidence<T>,
)

internal suspend fun <T> captureFinalDataPlaneEvidence(
    activeCollector: AtomicReference<DataPlaneEvidenceCollector?>,
    capture: suspend (DataPlaneEvidenceCollector) -> T,
    publish: suspend (T) -> Unit,
) {
    val collector = activeCollector.get() ?: return
    val finalEvidence = capture(collector)
    if (!activeCollector.compareAndSet(collector, null)) return
    var published = false
    try {
        publish(finalEvidence)
        published = true
    } finally {
        if (!published) activeCollector.compareAndSet(null, collector)
    }
}

/**
 * Correlates privacy-safe cumulative forwarding counters on the existing telemetry loop.
 *
 * This is cross-layer evidence, not flow attribution: a TUN return can be local DNS traffic,
 * and the proxy evidence exposes only application bytes sent upstream. The tracker therefore
 * never reports proxy inbound traffic or a same-flow round trip.
 */
internal class DataPlaneEvidenceCollector(
    private val mode: Mode,
    private val proxyEvidenceProvider: suspend () -> ProxyForwardingEvidence?,
    private val tunEvidenceProvider: (suspend () -> TunForwardingEvidence?)? = null,
    private val clock: ServiceClock = SystemServiceClock,
) {
    private val tracker = DataPlaneCorrelationTracker(mode, clock)
    private val pollMutex = Mutex()
    private val forwardingOutcomeTracker = DataPlaneForwardingOutcomeTracker()
    private var finalized = false
    private var lifecycleGeneration: Long? = null
    private var lifecycleResetPending = false

    suspend fun enrich(snapshot: VpnTelemetrySnapshot): VpnTelemetrySnapshot =
        pollMutex.withLock {
            if (finalized) replayOnto(snapshot) else pollAndEnrich(snapshot, finalCapture = false)
        }

    suspend fun enrich(
        snapshot: VpnTelemetrySnapshot,
        proxyEvidence: RuntimeForwardingEvidence<ProxyForwardingEvidence>,
        tunEvidence: RuntimeForwardingEvidence<TunForwardingEvidence> = RuntimeForwardingEvidence.Unavailable,
        lifecycleGeneration: Long? = null,
    ): VpnTelemetrySnapshot = enrichWithOutcome(snapshot, proxyEvidence, tunEvidence, lifecycleGeneration).snapshot

    suspend fun enrichWithOutcome(
        snapshot: VpnTelemetrySnapshot,
        proxyEvidence: RuntimeForwardingEvidence<ProxyForwardingEvidence>,
        tunEvidence: RuntimeForwardingEvidence<TunForwardingEvidence> = RuntimeForwardingEvidence.Unavailable,
        lifecycleGeneration: Long? = null,
    ): DataPlaneEvidenceResult =
        pollMutex.withLock {
            if (!bindLifecycleGenerationLocked(lifecycleGeneration)) {
                return@withLock DataPlaneEvidenceResult(replayOnto(snapshot), forwardingOutcomeTracker.current())
            }
            val enriched =
                if (finalized) {
                    replayOnto(snapshot)
                } else {
                    observeAndEnrich(snapshot, proxyEvidence, tunEvidence, finalCapture = false)
                }
            DataPlaneEvidenceResult(enriched, forwardingOutcomeTracker.current())
        }

    suspend fun finalizeAndEnrich(snapshot: VpnTelemetrySnapshot): VpnTelemetrySnapshot =
        pollMutex.withLock {
            if (finalized) return@withLock replayOnto(snapshot)
            val finalizedSnapshot = pollAndEnrich(snapshot, finalCapture = true)
            finalized = true
            finalizedSnapshot
        }

    suspend fun finalizeAndEnrich(
        snapshot: VpnTelemetrySnapshot,
        proxyEvidence: RuntimeForwardingEvidence<ProxyForwardingEvidence>,
        tunEvidence: RuntimeForwardingEvidence<TunForwardingEvidence> = RuntimeForwardingEvidence.Unavailable,
        lifecycleGeneration: Long? = null,
    ): VpnTelemetrySnapshot =
        finalizeAndEnrichWithOutcome(snapshot, proxyEvidence, tunEvidence, lifecycleGeneration).snapshot

    suspend fun finalizeAndEnrichWithOutcome(
        snapshot: VpnTelemetrySnapshot,
        proxyEvidence: RuntimeForwardingEvidence<ProxyForwardingEvidence>,
        tunEvidence: RuntimeForwardingEvidence<TunForwardingEvidence> = RuntimeForwardingEvidence.Unavailable,
        lifecycleGeneration: Long? = null,
    ): DataPlaneEvidenceResult =
        pollMutex.withLock {
            if (!bindLifecycleGenerationLocked(lifecycleGeneration)) {
                return@withLock DataPlaneEvidenceResult(replayOnto(snapshot), forwardingOutcomeTracker.current())
            }
            val enriched =
                if (finalized) {
                    replayOnto(snapshot)
                } else {
                    observeAndEnrich(snapshot, proxyEvidence, tunEvidence, finalCapture = true).also {
                        finalized = true
                    }
                }
            DataPlaneEvidenceResult(enriched, forwardingOutcomeTracker.current())
        }

    fun currentOutcome(): DataPlaneForwardingOutcome = forwardingOutcomeTracker.current()

    private fun bindLifecycleGenerationLocked(generation: Long?): Boolean {
        val currentGeneration = lifecycleGeneration
        return when {
            generation == null || currentGeneration == generation -> {
                true
            }

            currentGeneration != null && generation < currentGeneration -> {
                false
            }

            else -> {
                lifecycleGeneration = generation
                lifecycleResetPending = true
                true
            }
        }
    }

    private suspend fun pollAndEnrich(
        snapshot: VpnTelemetrySnapshot,
        finalCapture: Boolean,
    ): VpnTelemetrySnapshot {
        val proxyEvidence = pollSafely(proxyEvidenceProvider)
        val tunEvidence = tunEvidenceProvider?.let { pollSafely(it) }
        return observeAndEnrich(
            snapshot = snapshot,
            proxyEvidence = proxyEvidence.toRuntimeForwardingEvidence(),
            tunEvidence = tunEvidence.toRuntimeForwardingEvidence(),
            finalCapture = finalCapture,
            tunProviderSupported = tunEvidenceProvider != null,
        )
    }

    private fun observeAndEnrich(
        snapshot: VpnTelemetrySnapshot,
        proxyEvidence: RuntimeForwardingEvidence<ProxyForwardingEvidence>,
        tunEvidence: RuntimeForwardingEvidence<TunForwardingEvidence>,
        finalCapture: Boolean,
        tunProviderSupported: Boolean = mode == Mode.VPN,
    ): VpnTelemetrySnapshot {
        val proxyValue = proxyEvidence.valueOrNull()
        val tunValue = tunEvidence.valueOrNull()
        val tunSupport =
            if (!tunProviderSupported) {
                TunEvidenceSupport.Unsupported
            } else if (tunEvidence === RuntimeForwardingEvidence.Unavailable) {
                TunEvidenceSupport.Unavailable
            } else {
                TunEvidenceSupport.Supported
            }
        if (lifecycleResetPending) {
            tracker.beginLifecycleGeneration(proxyValue, tunValue, tunSupport)
            lifecycleResetPending = false
            forwardingOutcomeTracker.resetConfirmation()
        }
        tracker.observe(
            proxyEvidence = proxyValue,
            tunEvidence = tunValue,
            tunSupport = tunSupport,
            proxyGenerationAuthoritative =
                proxyEvidence is RuntimeForwardingEvidence.Available &&
                    snapshot.proxyTelemetry.hasAuthoritativeRunningGeneration(snapshot.proxyTelemetryStatus.state),
            tunGenerationAuthoritative =
                tunEvidence is RuntimeForwardingEvidence.Available &&
                    snapshot.tunnelTelemetry.hasAuthoritativeRunningGeneration(snapshot.tunnelTelemetryStatus.state),
        )
        if (finalCapture) tracker.finalEvent()
        forwardingOutcomeTracker.publish(tracker.currentState())
        return replayOnto(snapshot)
    }

    private fun replayOnto(snapshot: VpnTelemetrySnapshot): VpnTelemetrySnapshot {
        val replayEvents = tracker.events()
        if (replayEvents.isEmpty()) return snapshot
        return snapshot.copy(
            proxyTelemetry =
                snapshot.proxyTelemetry.copy(
                    nativeEvents = snapshot.proxyTelemetry.nativeEvents + replayEvents,
                ),
        )
    }

    private suspend fun <T> pollSafely(provider: suspend () -> T?): T? =
        try {
            provider()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
}

internal class DataPlaneCorrelationTracker(
    private val mode: Mode,
    private val clock: ServiceClock = SystemServiceClock,
    private val eventCap: Int = DataPlaneEventCap,
) {
    private var generation: Int = 1
    private var bestProxyEvidence: ProxyForwardingEvidence? = null
    private var bestTunEvidence: TunForwardingEvidence? = null
    private var lastProxyEvidence: ProxyForwardingEvidence? = null
    private var lastTunEvidence: TunForwardingEvidence? = null
    private var proxyEvidenceSeen: Boolean = false
    private var tunEvidenceSeen: Boolean = false
    private var proxyCurrentGenerationEvidenceSeen: Boolean = false
    private var tunCurrentGenerationEvidenceSeen: Boolean = false
    private var proxyGenerationBaseline: ProxyForwardingEvidence? = null
    private var tunGenerationBaseline: TunForwardingEvidence? = null
    private var bestTunSupport: TunEvidenceSupport =
        if (mode == Mode.Proxy) TunEvidenceSupport.Unsupported else TunEvidenceSupport.Unavailable
    private val replayEvents = ArrayDeque<NativeRuntimeEvent>(eventCap)
    private val emittedStates = mutableSetOf<DataPlaneCorrelationState>()
    private var previousState: DataPlaneCorrelationState? = null
    private var lastPublishedSummary: String? = null
    private var finalEventEmitted = false

    init {
        require(eventCap > 0)
    }

    fun observe(
        proxyEvidence: ProxyForwardingEvidence?,
        tunEvidence: TunForwardingEvidence?,
        tunSupport: TunEvidenceSupport,
        proxyGenerationAuthoritative: Boolean = false,
        tunGenerationAuthoritative: Boolean = false,
    ) {
        beginNextGenerationIfNeeded(
            proxyEvidence = proxyEvidence,
            tunEvidence = tunEvidence,
            proxyGenerationAuthoritative = proxyGenerationAuthoritative,
            tunGenerationAuthoritative = tunGenerationAuthoritative,
        )
        recordProxyEvidence(proxyEvidence)
        recordTunEvidence(tunEvidence)
        if (tunSupport == TunEvidenceSupport.Supported) bestTunSupport = tunSupport

        val state = classify()
        if (state == previousState || !emittedStates.add(state)) return
        previousState = state
        emitSummary(state, finalCapture = false)
    }

    private fun beginNextGenerationIfNeeded(
        proxyEvidence: ProxyForwardingEvidence?,
        tunEvidence: TunForwardingEvidence?,
        proxyGenerationAuthoritative: Boolean,
        tunGenerationAuthoritative: Boolean,
    ) {
        val proxyReset =
            proxyGenerationAuthoritative && proxyEvidence != null && lastProxyEvidence.hasResetTo(proxyEvidence)
        val tunReset = tunGenerationAuthoritative && tunEvidence != null && lastTunEvidence.hasResetTo(tunEvidence)
        if (!proxyReset && !tunReset) return
        beginNextGeneration(
            proxyReset = proxyReset,
            tunReset = tunReset,
            proxyEvidence = proxyEvidence,
            tunEvidence = tunEvidence,
        )
    }

    private fun recordProxyEvidence(evidence: ProxyForwardingEvidence?) {
        evidence ?: return
        proxyEvidenceSeen = true
        if (lastProxyEvidence == null || evidence.hasNonZeroEvidence()) lastProxyEvidence = evidence
        val baseline = proxyGenerationBaseline
        val currentGenerationEvidence = baseline?.let(evidence::deltaFrom) ?: evidence
        if (baseline != null && !currentGenerationEvidence.hasNonZeroEvidence()) return
        proxyCurrentGenerationEvidenceSeen = true
        bestProxyEvidence = bestProxyEvidence.merge(currentGenerationEvidence)
    }

    private fun recordTunEvidence(evidence: TunForwardingEvidence?) {
        evidence ?: return
        tunEvidenceSeen = true
        if (lastTunEvidence == null || evidence.hasNonZeroEvidence()) lastTunEvidence = evidence
        val baseline = tunGenerationBaseline
        val currentGenerationEvidence = baseline?.let(evidence::deltaFrom) ?: evidence
        if (baseline != null && !currentGenerationEvidence.hasNonZeroEvidence()) return
        tunCurrentGenerationEvidenceSeen = true
        bestTunEvidence = bestTunEvidence.merge(currentGenerationEvidence)
    }

    fun finalEvent() {
        if (finalEventEmitted) return
        finalEventEmitted = true
        emitSummary(classify(), finalCapture = true)
    }

    fun events(): List<NativeRuntimeEvent> = replayEvents.toList()

    fun currentState(): DataPlaneCorrelationState = previousState ?: DataPlaneCorrelationState.EvidenceUnavailable

    fun beginLifecycleGeneration(
        proxyEvidence: ProxyForwardingEvidence?,
        tunEvidence: TunForwardingEvidence?,
        tunSupport: TunEvidenceSupport,
    ) {
        generation += 1
        bestProxyEvidence = null
        bestTunEvidence = null
        lastProxyEvidence = proxyEvidence
        lastTunEvidence = tunEvidence
        proxyEvidenceSeen = proxyEvidence != null
        tunEvidenceSeen = tunEvidence != null
        proxyCurrentGenerationEvidenceSeen = false
        tunCurrentGenerationEvidenceSeen = false
        proxyGenerationBaseline = proxyEvidence
        tunGenerationBaseline = tunEvidence
        bestTunSupport = tunSupport
        emittedStates.clear()
        previousState = null
        lastPublishedSummary = null
        finalEventEmitted = false
    }

    private fun beginNextGeneration(
        proxyReset: Boolean,
        tunReset: Boolean,
        proxyEvidence: ProxyForwardingEvidence?,
        tunEvidence: TunForwardingEvidence?,
    ) {
        val proxyBaseline = if (proxyReset) null else proxyEvidence ?: lastProxyEvidence
        val tunBaseline = if (tunReset) null else tunEvidence ?: lastTunEvidence
        val resetLayers = listOfNotNull("proxy".takeIf { proxyReset }, "tun".takeIf { tunReset })
        generation += 1
        bestProxyEvidence = null
        bestTunEvidence = null
        lastProxyEvidence = null
        lastTunEvidence = null
        proxyEvidenceSeen = false
        tunEvidenceSeen = false
        proxyCurrentGenerationEvidenceSeen = false
        tunCurrentGenerationEvidenceSeen = false
        proxyGenerationBaseline = proxyBaseline
        tunGenerationBaseline = tunBaseline
        bestTunSupport =
            if (mode == Mode.Proxy) TunEvidenceSupport.Unsupported else TunEvidenceSupport.Unavailable
        emittedStates.clear()
        previousState = null
        lastPublishedSummary = null
        finalEventEmitted = false
        appendEvent(
            message =
                "state=counter_reset mode=${mode.preferenceValue} generation=$generation " +
                    "layers=${resetLayers.joinToString(",")}",
            kind = "data_plane_counter_reset",
        )
    }

    private fun emitSummary(
        state: DataPlaneCorrelationState,
        finalCapture: Boolean,
    ) {
        val summary =
            state.redactedMessage(
                mode = mode,
                generation = generation,
                proxyAvailable = proxyEvidenceSeen && proxyCurrentGenerationEvidenceSeen,
                tunSupport = bestTunSupport,
                tunAvailable = tunEvidenceSeen && tunCurrentGenerationEvidenceSeen,
                proxy = bestProxyEvidence,
                tun = bestTunEvidence,
            )
        val message = if (finalCapture) "$summary final=true" else summary
        if (appendEvent(message, if (finalCapture) "data_plane_final" else "data_plane_correlation")) {
            lastPublishedSummary = summary
        }
    }

    private fun appendEvent(
        message: String,
        kind: String,
    ): Boolean {
        if (replayEvents.size >= eventCap) replayEvents.removeFirst()
        replayEvents.addLast(
            NativeRuntimeEvent(
                source = "service",
                level = "info",
                message = message,
                createdAt = clock.nowMillis(),
                kind = kind,
                mode = mode.preferenceValue,
                subsystem = "data_plane",
            ),
        )
        return true
    }

    private fun classify(): DataPlaneCorrelationState {
        val proxy = bestProxyEvidence
        val tun = bestTunEvidence
        return if (!proxyEvidenceSeen && !tunEvidenceSeen) {
            DataPlaneCorrelationState.EvidenceUnavailable
        } else if (mode == Mode.VPN && !hasCurrentCrossLayerEvidence()) {
            DataPlaneCorrelationState.PartialEvidence
        } else if (mode == Mode.Proxy) {
            classifyProxy(proxy)
        } else {
            classifyVpn(proxy, tun)
        }
    }

    private fun hasCurrentCrossLayerEvidence(): Boolean =
        proxyEvidenceSeen &&
            tunEvidenceSeen &&
            proxyCurrentGenerationEvidenceSeen &&
            tunCurrentGenerationEvidenceSeen

    private fun classifyProxy(proxy: ProxyForwardingEvidence?): DataPlaneCorrelationState =
        when {
            proxy?.upstreamApplicationBytes.orZero() > 0 -> DataPlaneCorrelationState.ProxyOutboundObserved
            proxy?.upstreamOpened.orZero() > 0 -> DataPlaneCorrelationState.UpstreamOpenNoPayload
            else -> DataPlaneCorrelationState.NoFlow
        }

    private fun classifyVpn(
        proxy: ProxyForwardingEvidence?,
        tun: TunForwardingEvidence?,
    ): DataPlaneCorrelationState {
        val proxyOutbound = proxy?.upstreamApplicationBytes.orZero() > 0
        val upstreamOpened = proxy?.upstreamOpened.orZero() > 0
        val tunIngress = tun?.tunReadBytes.orZero() > 0
        val tunReturn = tun?.tunWriteBytes.orZero() > 0
        return when {
            proxyOutbound && tunReturn -> DataPlaneCorrelationState.CrossLayerReturnObserved
            proxyOutbound -> DataPlaneCorrelationState.OutboundOnly
            tunReturn -> DataPlaneCorrelationState.TunReturnWithoutProxyOutbound
            tunIngress && !upstreamOpened -> DataPlaneCorrelationState.TunIngressNoUpstream
            upstreamOpened -> DataPlaneCorrelationState.UpstreamOpenNoPayload
            else -> DataPlaneCorrelationState.NoFlow
        }
    }
}

internal enum class TunEvidenceSupport(
    val wireValue: String,
) {
    Unsupported("unsupported"),
    Unavailable("unavailable"),
    Supported("supported"),
}

internal enum class DataPlaneCorrelationState(
    val wireValue: String,
) {
    EvidenceUnavailable("evidence_unavailable"),
    PartialEvidence("evidence_unavailable_partial"),
    NoFlow("no_flow"),
    TunIngressNoUpstream("tun_ingress_no_upstream"),
    UpstreamOpenNoPayload("upstream_open_no_payload"),
    OutboundOnly("outbound_only"),
    TunReturnWithoutProxyOutbound("tun_return_without_proxy_outbound"),
    ProxyOutboundObserved("proxy_outbound_observed"),
    CrossLayerReturnObserved("cross_layer_return_observed"),
    ;

    val isTerminalFailure: Boolean
        get() =
            this == TunIngressNoUpstream ||
                this == UpstreamOpenNoPayload ||
                this == OutboundOnly

    fun redactedMessage(
        mode: Mode,
        generation: Int,
        proxyAvailable: Boolean,
        tunSupport: TunEvidenceSupport,
        tunAvailable: Boolean,
        proxy: ProxyForwardingEvidence?,
        tun: TunForwardingEvidence?,
    ): String {
        val proxyOutbound =
            when {
                !proxyAvailable -> "unavailable"
                proxy?.upstreamApplicationBytes.orZero() > 0 -> "observed"
                else -> "not_observed"
            }
        val tunReturn =
            when {
                !tunAvailable && tunSupport == TunEvidenceSupport.Unsupported -> "unsupported"
                !tunAvailable -> "unavailable"
                tun?.tunWriteBytes.orZero() > 0 -> "observed"
                else -> "not_observed"
            }
        val inference =
            if (this == CrossLayerReturnObserved) "cross_layer_not_same_flow" else "none"
        val tunErrors = tun?.let { safeSum(it.tunReadErrors, it.tunWriteErrors, it.tunParseFailures) } ?: "none"
        val tunDrops = tun?.let { safeSum(it.tunPolicyDrops, it.tunInterceptorDrops, it.tunQueueDrops) } ?: "none"
        val proxyAccepted = evidenceCount(proxy?.proxyClientSocketsAccepted, proxy != null)
        val proxySocketsCreated = evidenceCount(proxy?.upstreamSocketCreated, proxy != null)
        val proxyOpened = evidenceCount(proxy?.upstreamOpened, proxy != null)
        val proxyOpenFailures = evidenceCount(proxy?.upstreamOpenFailures, proxy != null)
        val proxyApplicationBytes = evidenceCount(proxy?.upstreamApplicationBytes, proxy != null)
        val tunReadPackets = evidenceCount(tun?.tunReadPackets, tun != null)
        val tunReadBytes = evidenceCount(tun?.tunReadBytes, tun != null)
        val tunWritePackets = evidenceCount(tun?.tunWritePackets, tun != null)
        val tunWriteBytes = evidenceCount(tun?.tunWriteBytes, tun != null)
        val proxyAvailability = if (proxyAvailable) "available" else "unavailable"
        val tunAvailability = if (tunAvailable) "available" else tunSupport.wireValue
        return "state=$wireValue mode=${mode.preferenceValue} generation=$generation " +
            "proxy_evidence=$proxyAvailability proxy_outbound=$proxyOutbound proxy_inbound=unavailable " +
            "tun_support=$tunAvailability tun_return=$tunReturn " +
            "inference=$inference proxy_clients_accepted=$proxyAccepted " +
            "upstream_sockets_created=$proxySocketsCreated " +
            "upstream_opened=$proxyOpened upstream_open_failures=$proxyOpenFailures " +
            "proxy_application_bytes=$proxyApplicationBytes " +
            "first_proxy_application_at=${proxy?.firstUpstreamApplicationForwardedAt.safeTimestamp()} " +
            "last_proxy_application_at=${proxy?.lastUpstreamApplicationForwardedAt.safeTimestamp()} " +
            "tun_read_packets=$tunReadPackets tun_read_bytes=$tunReadBytes " +
            "tun_write_packets=$tunWritePackets tun_write_bytes=$tunWriteBytes " +
            "tun_errors_total=$tunErrors tun_drops_total=$tunDrops " +
            "first_tun_write_at=${tun?.firstTunWriteAtEpochMs.safeTimestamp()} " +
            "last_tun_write_at=${tun?.lastTunWriteAtEpochMs.safeTimestamp()}"
    }
}

private fun Long?.orZero(): Long = this ?: 0L

private fun NativeRuntimeSnapshot.hasAuthoritativeRunningGeneration(status: RuntimeTelemetryState): Boolean =
    status == RuntimeTelemetryState.Snapshot && state == "running"

private fun <T> T?.toRuntimeForwardingEvidence(): RuntimeForwardingEvidence<T> =
    this?.let { value -> RuntimeForwardingEvidence.Available(value) } ?: RuntimeForwardingEvidence.Unavailable

private fun <T> RuntimeForwardingEvidence<T>.valueOrNull(): T? =
    (this as? RuntimeForwardingEvidence.Available<T>)?.value

private fun Long?.safe(): Long = this?.coerceAtLeast(0) ?: 0L

private fun evidenceCount(
    value: Long?,
    available: Boolean,
): String = if (available) value.safe().toString() else "none"

private fun Long?.safeTimestamp(): String = this?.takeIf { it >= 0 }?.toString() ?: "none"

private fun safeSum(vararg values: Long?): String {
    val total =
        values.fold(0L) { sum, value ->
            val safeValue = value.safe()
            if (Long.MAX_VALUE - sum < safeValue) Long.MAX_VALUE else sum + safeValue
        }
    return total.toString()
}

private fun ProxyForwardingEvidence?.merge(incoming: ProxyForwardingEvidence): ProxyForwardingEvidence =
    this?.let { current ->
        ProxyForwardingEvidence(
            proxyClientSocketsAccepted = maxOf(current.proxyClientSocketsAccepted, incoming.proxyClientSocketsAccepted),
            upstreamSocketCreated = maxOf(current.upstreamSocketCreated, incoming.upstreamSocketCreated),
            upstreamOpened = maxOf(current.upstreamOpened, incoming.upstreamOpened),
            upstreamOpenFailures = maxOf(current.upstreamOpenFailures, incoming.upstreamOpenFailures),
            protectAttempted = maxOf(current.protectAttempted, incoming.protectAttempted),
            protectSucceeded = maxOf(current.protectSucceeded, incoming.protectSucceeded),
            protectRejected = maxOf(current.protectRejected, incoming.protectRejected),
            protectErrors = maxOf(current.protectErrors, incoming.protectErrors),
            upstreamApplicationBytes = maxOf(current.upstreamApplicationBytes, incoming.upstreamApplicationBytes),
            firstUpstreamApplicationForwardedAt =
                earliest(current.firstUpstreamApplicationForwardedAt, incoming.firstUpstreamApplicationForwardedAt),
            lastUpstreamApplicationForwardedAt =
                latest(current.lastUpstreamApplicationForwardedAt, incoming.lastUpstreamApplicationForwardedAt),
        )
    } ?: incoming

private fun TunForwardingEvidence?.merge(incoming: TunForwardingEvidence): TunForwardingEvidence =
    this?.let { current ->
        TunForwardingEvidence(
            tunReadPackets = maxOf(current.tunReadPackets, incoming.tunReadPackets),
            tunReadBytes = maxOf(current.tunReadBytes, incoming.tunReadBytes),
            tunWritePackets = maxOf(current.tunWritePackets, incoming.tunWritePackets),
            tunWriteBytes = maxOf(current.tunWriteBytes, incoming.tunWriteBytes),
            tunReadErrors = maxOf(current.tunReadErrors, incoming.tunReadErrors),
            tunWriteErrors = maxOf(current.tunWriteErrors, incoming.tunWriteErrors),
            tunParseFailures = maxOf(current.tunParseFailures, incoming.tunParseFailures),
            tunPolicyDrops = maxOf(current.tunPolicyDrops, incoming.tunPolicyDrops),
            tunInterceptorDrops = maxOf(current.tunInterceptorDrops, incoming.tunInterceptorDrops),
            tunQueueDrops = maxOf(current.tunQueueDrops, incoming.tunQueueDrops),
            firstTunWriteAtEpochMs = earliest(current.firstTunWriteAtEpochMs, incoming.firstTunWriteAtEpochMs),
            lastTunWriteAtEpochMs = latest(current.lastTunWriteAtEpochMs, incoming.lastTunWriteAtEpochMs),
        )
    } ?: incoming

private fun earliest(
    current: Long?,
    incoming: Long?,
): Long? = listOfNotNull(current, incoming).minOrNull()

private fun latest(
    current: Long?,
    incoming: Long?,
): Long? = listOfNotNull(current, incoming).maxOrNull()

private fun ProxyForwardingEvidence?.hasResetTo(incoming: ProxyForwardingEvidence): Boolean {
    val current = this ?: return false
    val decreased =
        listOf(
            current.proxyClientSocketsAccepted to incoming.proxyClientSocketsAccepted,
            current.upstreamSocketCreated to incoming.upstreamSocketCreated,
            current.upstreamOpened to incoming.upstreamOpened,
            current.upstreamOpenFailures to incoming.upstreamOpenFailures,
            current.protectAttempted to incoming.protectAttempted,
            current.protectSucceeded to incoming.protectSucceeded,
            current.protectRejected to incoming.protectRejected,
            current.protectErrors to incoming.protectErrors,
            current.upstreamApplicationBytes to incoming.upstreamApplicationBytes,
        ).any { (before, after) -> after < before }
    return decreased
}

private fun TunForwardingEvidence?.hasResetTo(incoming: TunForwardingEvidence): Boolean {
    val current = this ?: return false
    val decreased =
        listOf(
            current.tunReadPackets to incoming.tunReadPackets,
            current.tunReadBytes to incoming.tunReadBytes,
            current.tunWritePackets to incoming.tunWritePackets,
            current.tunWriteBytes to incoming.tunWriteBytes,
            current.tunReadErrors to incoming.tunReadErrors,
            current.tunWriteErrors to incoming.tunWriteErrors,
            current.tunParseFailures to incoming.tunParseFailures,
            current.tunPolicyDrops to incoming.tunPolicyDrops,
            current.tunInterceptorDrops to incoming.tunInterceptorDrops,
            current.tunQueueDrops to incoming.tunQueueDrops,
        ).any { (before, after) -> after < before }
    return decreased
}

private fun ProxyForwardingEvidence.deltaFrom(baseline: ProxyForwardingEvidence): ProxyForwardingEvidence {
    val applicationBytesDelta = counterDelta(upstreamApplicationBytes, baseline.upstreamApplicationBytes)
    val applicationTimestamp =
        if (applicationBytesDelta > 0) {
            lastUpstreamApplicationForwardedAt ?: firstUpstreamApplicationForwardedAt
        } else {
            null
        }
    return ProxyForwardingEvidence(
        proxyClientSocketsAccepted = counterDelta(proxyClientSocketsAccepted, baseline.proxyClientSocketsAccepted),
        upstreamSocketCreated = counterDelta(upstreamSocketCreated, baseline.upstreamSocketCreated),
        upstreamOpened = counterDelta(upstreamOpened, baseline.upstreamOpened),
        upstreamOpenFailures = counterDelta(upstreamOpenFailures, baseline.upstreamOpenFailures),
        protectAttempted = counterDelta(protectAttempted, baseline.protectAttempted),
        protectSucceeded = counterDelta(protectSucceeded, baseline.protectSucceeded),
        protectRejected = counterDelta(protectRejected, baseline.protectRejected),
        protectErrors = counterDelta(protectErrors, baseline.protectErrors),
        upstreamApplicationBytes = applicationBytesDelta,
        firstUpstreamApplicationForwardedAt = applicationTimestamp,
        lastUpstreamApplicationForwardedAt = applicationTimestamp,
    )
}

private fun TunForwardingEvidence.deltaFrom(baseline: TunForwardingEvidence): TunForwardingEvidence {
    val writePacketsDelta = counterDelta(tunWritePackets, baseline.tunWritePackets)
    val writeBytesDelta = counterDelta(tunWriteBytes, baseline.tunWriteBytes)
    val writeTimestamp =
        if (writePacketsDelta > 0 || writeBytesDelta > 0) {
            lastTunWriteAtEpochMs ?: firstTunWriteAtEpochMs
        } else {
            null
        }
    return TunForwardingEvidence(
        tunReadPackets = counterDelta(tunReadPackets, baseline.tunReadPackets),
        tunReadBytes = counterDelta(tunReadBytes, baseline.tunReadBytes),
        tunWritePackets = writePacketsDelta,
        tunWriteBytes = writeBytesDelta,
        tunReadErrors = counterDelta(tunReadErrors, baseline.tunReadErrors),
        tunWriteErrors = counterDelta(tunWriteErrors, baseline.tunWriteErrors),
        tunParseFailures = counterDelta(tunParseFailures, baseline.tunParseFailures),
        tunPolicyDrops = counterDelta(tunPolicyDrops, baseline.tunPolicyDrops),
        tunInterceptorDrops = counterDelta(tunInterceptorDrops, baseline.tunInterceptorDrops),
        tunQueueDrops = counterDelta(tunQueueDrops, baseline.tunQueueDrops),
        firstTunWriteAtEpochMs = writeTimestamp,
        lastTunWriteAtEpochMs = writeTimestamp,
    )
}

private fun counterDelta(
    current: Long,
    baseline: Long,
): Long {
    val safeCurrent = current.coerceAtLeast(0)
    val safeBaseline = baseline.coerceAtLeast(0)
    return if (safeCurrent > safeBaseline) safeCurrent - safeBaseline else 0
}

private fun ProxyForwardingEvidence.hasNonZeroEvidence(): Boolean =
    proxyClientSocketsAccepted > 0 ||
        upstreamSocketCreated > 0 ||
        upstreamOpened > 0 ||
        upstreamOpenFailures > 0 ||
        protectAttempted > 0 ||
        protectSucceeded > 0 ||
        protectRejected > 0 ||
        protectErrors > 0 ||
        upstreamApplicationBytes > 0

private fun TunForwardingEvidence.hasNonZeroEvidence(): Boolean =
    tunReadPackets > 0 ||
        tunReadBytes > 0 ||
        tunWritePackets > 0 ||
        tunWriteBytes > 0 ||
        tunReadErrors > 0 ||
        tunWriteErrors > 0 ||
        tunParseFailures > 0 ||
        tunPolicyDrops > 0 ||
        tunInterceptorDrops > 0 ||
        tunQueueDrops > 0
