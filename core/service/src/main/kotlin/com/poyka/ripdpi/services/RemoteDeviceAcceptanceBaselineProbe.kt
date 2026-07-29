package com.poyka.ripdpi.services

import android.os.SystemClock
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.AuthoritativeVpnUnderlayObservationProvider
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkPathObservation
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.URI
import java.util.Optional
import javax.inject.Inject

internal class RemoteDeviceAcceptanceBaselineProbe internal constructor(
    private val serviceStateStore: ServiceStateStore,
    private val relayCapabilityProbe: RelayCapabilityProbe,
    private val underlayObservationProvider: AuthoritativeVpnUnderlayObservationProvider,
    private val awgEgressSelectionProvider: AwgEgressSelectionProvider = NoSelectedAwgEgressProvider,
    private val deviceProvider: () -> RemoteDeviceAcceptanceDevice,
    private val monotonicClock: () -> Long,
    private val probeTargets: RemoteAcceptanceProbeTargets = RemoteAcceptanceProbeTargets(),
    private val payloadHealthCache: RelayUdpPayloadHealthCache = RelayUdpPayloadHealthCache(),
    private val appliedNetworkReceiptStore: VpnTunnelAppliedNetworkReceiptStore =
        VpnTunnelAppliedNetworkReceiptStore(),
) {
    @Inject
    constructor(
        serviceStateStore: ServiceStateStore,
        relayCapabilityProbe: RelayCapabilityProbe,
        underlayObservationProvider: AuthoritativeVpnUnderlayObservationProvider,
        awgEgressSelectionProvider: AwgEgressSelectionProvider,
        probeTargetsProvider: Optional<RemoteAcceptanceProbeTargetsProvider>,
        appliedNetworkReceiptStore: VpnTunnelAppliedNetworkReceiptStore,
    ) : this(
        serviceStateStore,
        relayCapabilityProbe,
        underlayObservationProvider,
        awgEgressSelectionProvider,
        ::captureRemoteDeviceAcceptanceDevice,
        SystemClock::elapsedRealtime,
        probeTargetsProvider
            .map(RemoteAcceptanceProbeTargetsProvider::targets)
            .orElseGet(::RemoteAcceptanceProbeTargets),
        appliedNetworkReceiptStore = appliedNetworkReceiptStore,
    )

    /**
     * Uses only the local relay listener, so own-app VPN exclusion cannot bypass the tested egress.
     * cancel-safe: every network operation is bounded and propagates cancellation.
     */
    suspend fun capture(snapshot: ServiceTelemetrySnapshot): RemoteDeviceAcceptanceReport {
        val startedAt = monotonicClock()
        val before = captureContext(snapshot)
        val probeEvidence = captureRelayEvidence(before)
        val after = captureContext(serviceStateStore.telemetry.value)
        val contextError = before.driftError(after)
        val pathPolicyAssessment =
            when {
                before.relayFallbackObserved || after.relayFallbackObserved -> {
                    AcceptancePathPolicyAssessment.Inconsistent
                }

                contextError != null -> {
                    AcceptancePathPolicyAssessment.Inconclusive
                }

                else -> {
                    before.pathPolicyAssessment()
                }
            }
        val underlay =
            before.underlayObservation.toRemoteDeviceAcceptanceUnderlay(
                before.appliedNetworkReceipt.takeIf { contextError == null },
            )
        return buildRemoteDeviceAcceptanceBaseline(
            device = deviceProvider(),
            evidence =
                AcceptanceBaselineEvidence(
                    serviceRunning = before.serviceRunning,
                    transportKind = before.transportKind,
                    listenerAvailable = before.endpoint != null,
                    probe = probeEvidence.connectivity,
                    ipv4Probe = probeEvidence.ipv4,
                    ipv6Probe = probeEvidence.ipv6,
                    payloadHealth = probeEvidence.payloadHealth.takeIf { contextError == null },
                    contextError = contextError ?: probeEvidence.contextError,
                    underlay = underlay,
                    pathPolicyAssessment = pathPolicyAssessment,
                    durationMs = (monotonicClock() - startedAt).coerceAtLeast(0L),
                    probePlan = before.probePlan,
                    awgRuntimeHealthy = before.awgRuntimeHealthy,
                ),
        )
    }

    private suspend fun captureContext(snapshot: ServiceTelemetrySnapshot): AcceptanceCaptureContext {
        val (status, mode) = serviceStateStore.status.value
        return AcceptanceCaptureContext(
            status = status,
            mode = mode,
            relayProtocolKind = sanitizeTransportKind(snapshot.relayTelemetry.protocolKind),
            relayListenerAddress = snapshot.relayTelemetry.listenerAddress?.trim(),
            awgRuntimePublished = snapshot.awgTelemetryStatus.state == RuntimeTelemetryState.Snapshot,
            awgRuntimeHealth = snapshot.awgTelemetry.health,
            awgSelected = selectedAwgEgressStatus(),
            serviceStartedAt = snapshot.serviceStartedAt,
            relayFallbackObserved = snapshot.relayFailed,
            underlayObservation = underlayObservationProvider.capture(),
            appliedNetworkReceipt = appliedNetworkReceiptStore.snapshot(),
        )
    }

    private suspend fun selectedAwgEgressStatus(): Boolean? =
        try {
            awgEgressSelectionProvider.selectedAwgEgress() != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private suspend fun captureRelayEvidence(context: AcceptanceCaptureContext): AcceptanceRelayEvidence {
        val endpoint = context.endpoint
        val unavailableEvidence =
            when {
                !context.serviceRunning || !context.probePlan.requiresRelayListener || endpoint == null -> {
                    AcceptanceRelayEvidence()
                }

                !probeTargets.hasRequiredTargetFor(
                    context.probePlan,
                    context.underlayObservation.mandatoryRelayUdpPayloadFamilies(),
                ) -> {
                    AcceptanceRelayEvidence(contextError = ErrorRemoteAcceptanceProbeTargetMissing)
                }

                else -> {
                    null
                }
            }
        if (unavailableEvidence != null) return unavailableEvidence
        val relayEndpoint = checkNotNull(endpoint)
        val families = context.underlayObservation.mandatoryRelayUdpPayloadFamilies()
        return coroutineScope {
            val connectivity =
                async {
                    probeOrNull(
                        relayEndpoint,
                        probeTargets.connectivityUrl.requireTarget(),
                        EgressRequirements(
                            tcpConnect = context.probePlan.relayTcp == AcceptanceProbeApplicability.Required,
                            udpAssociate = context.probePlan.relayUdp == AcceptanceProbeApplicability.Required,
                            udpAssociateTarget = probeTargets.udpAssociateTarget,
                        ),
                    )
                }
            val ipv4 =
                async {
                    if (context.probePlan.relayTcp == AcceptanceProbeApplicability.Required) {
                        probeOrNull(relayEndpoint, probeTargets.ipv4Url.requireTarget(), TcpOnlyRequirements)
                    } else {
                        null
                    }
                }
            val ipv6 =
                async {
                    if (context.probePlan.relayTcp == AcceptanceProbeApplicability.Required) {
                        probeOrNull(relayEndpoint, probeTargets.ipv6Url.requireTarget(), TcpOnlyRequirements)
                    } else {
                        null
                    }
                }
            val payloadHealth =
                async {
                    if (context.probePlan.relayUdpPayload == AcceptanceProbeApplicability.Required) {
                        payloadHealthOrNull(
                            endpoint = relayEndpoint,
                            families = families,
                            targets = probeTargets.udpPayloadTargets,
                            underlayGeneration = context.underlayObservation.generation,
                            serviceStartedAt = context.serviceStartedAt,
                        )
                    } else {
                        null
                    }
                }
            AcceptanceRelayEvidence(connectivity.await(), ipv4.await(), ipv6.await(), payloadHealth.await())
        }
    }

    private suspend fun probeOrNull(
        endpoint: RelayProbeEndpoint,
        url: String,
        requirements: EgressRequirements,
    ): RelayCapabilityProbeEvidence? =
        try {
            relayCapabilityProbe.probeEvidence(
                endpoint = endpoint,
                url = url,
                requirements = requirements,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private suspend fun payloadHealthOrNull(
        endpoint: RelayProbeEndpoint,
        families: Set<RelayUdpPayloadFamily>,
        targets: Map<RelayUdpPayloadFamily, InetSocketAddress>,
        underlayGeneration: Long?,
        serviceStartedAt: Long?,
    ): RelayUdpPayloadHealthEvidence? =
        if (underlayGeneration == null) {
            loadPayloadHealth(endpoint, families, targets)
        } else {
            payloadHealthCache.getOrPut(
                key =
                    RelayUdpPayloadHealthCacheKey(
                        endpoint,
                        underlayGeneration,
                        serviceStartedAt,
                        families,
                        probeTargets.catalogVersion,
                    ),
                nowMs = monotonicClock(),
            ) {
                loadPayloadHealth(endpoint, families, targets)
            }
        }

    private suspend fun loadPayloadHealth(
        endpoint: RelayProbeEndpoint,
        families: Set<RelayUdpPayloadFamily>,
        targets: Map<RelayUdpPayloadFamily, InetSocketAddress>,
    ): RelayUdpPayloadHealthEvidence? =
        try {
            relayCapabilityProbe.probePayloadHealth(endpoint, families, targets)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
}

private data class AcceptanceRelayEvidence(
    val connectivity: RelayCapabilityProbeEvidence? = null,
    val ipv4: RelayCapabilityProbeEvidence? = null,
    val ipv6: RelayCapabilityProbeEvidence? = null,
    val payloadHealth: RelayUdpPayloadHealthEvidence? = null,
    val contextError: String? = null,
)

internal data class RemoteAcceptanceProbeTargets(
    val catalogVersion: String? = null,
    val connectivityUrl: String? = null,
    val ipv4Url: String? = null,
    val ipv6Url: String? = null,
    val udpAssociateTarget: InetSocketAddress? = null,
    val udpPayloadTargets: Map<RelayUdpPayloadFamily, InetSocketAddress> = emptyMap(),
) {
    fun hasRequiredTargetFor(
        plan: AcceptanceTransportProbePlan,
        payloadFamilies: Set<RelayUdpPayloadFamily>,
    ): Boolean =
        !catalogVersion.isNullOrBlank() &&
            connectivityUrl.isValidAcceptanceProbeUrl() &&
            (
                plan.relayTcp != AcceptanceProbeApplicability.Required ||
                    (ipv4Url.isValidAcceptanceProbeUrl() && ipv6Url.isValidAcceptanceProbeUrl())
            ) &&
            (plan.relayUdp != AcceptanceProbeApplicability.Required || udpAssociateTarget.isValidProbeTarget()) &&
            (
                plan.relayUdpPayload != AcceptanceProbeApplicability.Required ||
                    payloadFamilies.all { family -> udpPayloadTargets[family].isValidProbeTarget() }
            )
}

internal fun interface RemoteAcceptanceProbeTargetsProvider {
    fun targets(): RemoteAcceptanceProbeTargets
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RemoteAcceptanceProbeTargetsProviderModule {
    @BindsOptionalOf
    abstract fun bindOptionalRemoteAcceptanceProbeTargetsProvider(): RemoteAcceptanceProbeTargetsProvider
}

private fun String?.isValidAcceptanceProbeUrl(): Boolean =
    this?.let { value ->
        runCatching { URI(value) }
            .getOrNull()
            ?.let { uri ->
                uri.scheme.equals("https", ignoreCase = true) &&
                    !uri.host.isNullOrBlank() &&
                    uri.rawUserInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null
            }
    } == true

private fun InetSocketAddress?.isValidProbeTarget(): Boolean =
    this != null && port in ValidProbePortRange && !hostString.isNullOrBlank()

private data class AcceptanceCaptureContext(
    val status: AppStatus,
    val mode: Mode?,
    val relayProtocolKind: String,
    val relayListenerAddress: String?,
    val awgRuntimePublished: Boolean,
    val awgRuntimeHealth: String,
    val awgSelected: Boolean?,
    val serviceStartedAt: Long?,
    val relayFallbackObserved: Boolean,
    val underlayObservation: NetworkPathObservation,
    val appliedNetworkReceipt: VpnTunnelAppliedNetworkReceipt?,
) {
    val serviceRunning: Boolean
        get() = status == AppStatus.Running && mode == Mode.VPN

    val transportKind: String
        get() = probePlan.transportKind

    val probePlan: AcceptanceTransportProbePlan
        get() = acceptanceTransportProbePlan(relayProtocolKind, awgSelected)

    val awgRuntimeHealthy: Boolean?
        get() =
            awgRuntimeHealth
                .takeIf { awgSelected == true && awgRuntimePublished }
                ?.let { it == HealthyRuntimeState }

    val endpoint: RelayProbeEndpoint?
        get() = parseLocalRelayEndpoint(relayListenerAddress)

    fun pathPolicyAssessment(): AcceptancePathPolicyAssessment =
        when {
            relayFallbackObserved -> {
                AcceptancePathPolicyAssessment.Inconsistent
            }

            !serviceRunning || awgSelected == null -> {
                AcceptancePathPolicyAssessment.Unavailable
            }

            awgSelected -> {
                if (awgRuntimePublished && awgRuntimeHealth == HealthyRuntimeState) {
                    AcceptancePathPolicyAssessment.Consistent
                } else {
                    AcceptancePathPolicyAssessment.Inconclusive
                }
            }

            probePlan.requiresRelayListener && endpoint != null -> {
                AcceptancePathPolicyAssessment.Consistent
            }

            probePlan.requiresRelayListener -> {
                AcceptancePathPolicyAssessment.Inconclusive
            }

            else -> {
                AcceptancePathPolicyAssessment.Unavailable
            }
        }

    fun driftError(after: AcceptanceCaptureContext): String? =
        ErrorPayloadHealthContextDrift.takeIf {
            status != after.status ||
                mode != after.mode ||
                relayProtocolKind != after.relayProtocolKind ||
                relayListenerAddress != after.relayListenerAddress ||
                awgRuntimePublished != after.awgRuntimePublished ||
                awgRuntimeHealth != after.awgRuntimeHealth ||
                awgSelected != after.awgSelected ||
                serviceStartedAt != after.serviceStartedAt ||
                relayFallbackObserved != after.relayFallbackObserved ||
                underlayObservation.generation != after.underlayObservation.generation ||
                appliedNetworkReceipt != after.appliedNetworkReceipt
        }
}

internal data class RelayUdpPayloadHealthCacheKey(
    val endpoint: RelayProbeEndpoint,
    val underlayGeneration: Long?,
    val serviceStartedAt: Long?,
    val families: Set<RelayUdpPayloadFamily>,
    val targetCatalogVersion: String? = null,
)

internal data class CachedRelayUdpPayloadHealth(
    val capturedAtMs: Long,
    val evidence: RelayUdpPayloadHealthEvidence?,
)

internal class RelayUdpPayloadHealthCache(
    private val maxEntries: Int = MaxPayloadHealthCacheEntries,
    private val cooldownMs: Long = PayloadHealthCacheCooldownMs,
) {
    private val entries = LinkedHashMap<RelayUdpPayloadHealthCacheKey, CachedRelayUdpPayloadHealth>()
    private val inFlight =
        mutableMapOf<RelayUdpPayloadHealthCacheKey, CompletableDeferred<RelayUdpPayloadHealthEvidence?>>()
    private val mutex = Mutex()

    suspend fun getOrPut(
        key: RelayUdpPayloadHealthCacheKey,
        nowMs: Long,
        loader: suspend () -> RelayUdpPayloadHealthEvidence?,
    ): RelayUdpPayloadHealthEvidence? {
        var result: RelayUdpPayloadHealthEvidence? = null
        var completed = false
        while (!completed) {
            when (val access = reserve(key, nowMs)) {
                is PayloadCacheAccess.Cached -> {
                    result = access.evidence
                    completed = true
                }

                is PayloadCacheAccess.Pending -> {
                    val resolution = resolvePending(key, nowMs, access, loader)
                    result = resolution.evidence
                    completed = resolution.completed
                }
            }
        }
        return result
    }

    private suspend fun resolvePending(
        key: RelayUdpPayloadHealthCacheKey,
        nowMs: Long,
        access: PayloadCacheAccess.Pending,
        loader: suspend () -> RelayUdpPayloadHealthEvidence?,
    ): PayloadCacheResolution =
        if (access.isLeader) {
            PayloadCacheResolution(loadAndPublish(key, nowMs, access.deferred, loader), completed = true)
        } else {
            try {
                PayloadCacheResolution(access.deferred.await(), completed = true)
            } catch (_: CancellationException) {
                currentCoroutineContext().ensureActive()
                PayloadCacheResolution(evidence = null, completed = false)
            }
        }

    private suspend fun reserve(
        key: RelayUdpPayloadHealthCacheKey,
        nowMs: Long,
    ): PayloadCacheAccess =
        mutex.withLock {
            val cached = freshEntry(key, nowMs)
            if (cached != null) {
                PayloadCacheAccess.Cached(cached.evidence)
            } else {
                val pending = inFlight[key]
                if (pending != null) {
                    PayloadCacheAccess.Pending(pending, isLeader = false)
                } else {
                    val deferred = CompletableDeferred<RelayUdpPayloadHealthEvidence?>()
                    inFlight[key] = deferred
                    PayloadCacheAccess.Pending(deferred, isLeader = true)
                }
            }
        }

    private suspend fun loadAndPublish(
        key: RelayUdpPayloadHealthCacheKey,
        nowMs: Long,
        deferred: CompletableDeferred<RelayUdpPayloadHealthEvidence?>,
        loader: suspend () -> RelayUdpPayloadHealthEvidence?,
    ): RelayUdpPayloadHealthEvidence? {
        val loadResult =
            runCatching {
                loader().also { loaded ->
                    mutex.withLock {
                        entries[key] = CachedRelayUdpPayloadHealth(nowMs, loaded)
                        trimLocked()
                    }
                }
            }
        return try {
            loadResult.getOrThrow()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { inFlight.remove(key) }
                loadResult.fold(deferred::complete, deferred::completeExceptionally)
            }
        }
    }

    private fun freshEntry(
        key: RelayUdpPayloadHealthCacheKey,
        nowMs: Long,
    ): CachedRelayUdpPayloadHealth? {
        val cached = entries[key] ?: return null
        return cached.takeIf { nowMs - it.capturedAtMs in 0..cooldownMs }
    }

    private fun trimLocked() {
        while (entries.size > maxEntries) {
            entries.remove(entries.keys.first())
        }
    }

    private sealed interface PayloadCacheAccess {
        data class Cached(
            val evidence: RelayUdpPayloadHealthEvidence?,
        ) : PayloadCacheAccess

        data class Pending(
            val deferred: CompletableDeferred<RelayUdpPayloadHealthEvidence?>,
            val isLeader: Boolean,
        ) : PayloadCacheAccess
    }

    private data class PayloadCacheResolution(
        val evidence: RelayUdpPayloadHealthEvidence?,
        val completed: Boolean,
    )
}

private fun parseLocalRelayEndpoint(listenerAddress: String?): RelayProbeEndpoint? =
    listenerAddress
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { raw ->
            runCatching {
                val uri = URI("socks://$raw")
                val endpoint = RelayProbeEndpoint(uri.host.orEmpty(), uri.port)
                endpoint.takeIf {
                    endpoint.host.isNotBlank() &&
                        endpoint.port in 1..MaxNetworkPort &&
                        InetSocketAddress(endpoint.host, endpoint.port).address?.isLoopbackAddress == true
                }
            }.getOrNull()
        }

private fun String?.requireTarget(): String = checkNotNull(this)

private val TcpOnlyRequirements = EgressRequirements(tcpConnect = true, udpAssociate = false)
private const val MaxNetworkPort = 65_535
private const val MaxPayloadHealthCacheEntries = 16
private const val PayloadHealthCacheCooldownMs = 60_000L
private val ValidProbePortRange = 1..65_535
private const val HealthyRuntimeState = "healthy"

private object NoSelectedAwgEgressProvider : AwgEgressSelectionProvider {
    override suspend fun selectedAwgEgress(): com.poyka.ripdpi.data.awg.AwgActivationRequest? = null
}
