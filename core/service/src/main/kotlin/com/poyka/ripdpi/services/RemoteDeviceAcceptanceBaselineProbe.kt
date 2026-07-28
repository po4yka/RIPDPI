package com.poyka.ripdpi.services

import android.os.SystemClock
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.AuthoritativeVpnUnderlayObservationProvider
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkPathObservation
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.net.URI
import javax.inject.Inject

internal class RemoteDeviceAcceptanceBaselineProbe internal constructor(
    private val serviceStateStore: ServiceStateStore,
    private val relayCapabilityProbe: RelayCapabilityProbe,
    private val underlayObservationProvider: AuthoritativeVpnUnderlayObservationProvider,
    private val deviceProvider: () -> RemoteDeviceAcceptanceDevice,
    private val monotonicClock: () -> Long,
    private val payloadHealthCache: RelayUdpPayloadHealthCache = RelayUdpPayloadHealthCache(),
) {
    @Inject
    constructor(
        serviceStateStore: ServiceStateStore,
        relayCapabilityProbe: RelayCapabilityProbe,
        underlayObservationProvider: AuthoritativeVpnUnderlayObservationProvider,
    ) : this(
        serviceStateStore,
        relayCapabilityProbe,
        underlayObservationProvider,
        ::captureRemoteDeviceAcceptanceDevice,
        SystemClock::elapsedRealtime,
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
        val underlay = before.underlayObservation.toRemoteDeviceAcceptanceUnderlay()
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
                    contextError = contextError,
                    underlay = underlay,
                    directEgressObserved = snapshot.relayFailed,
                    durationMs = (monotonicClock() - startedAt).coerceAtLeast(0L),
                ),
        )
    }

    private fun captureContext(snapshot: ServiceTelemetrySnapshot): AcceptanceCaptureContext {
        val (status, mode) = serviceStateStore.status.value
        return AcceptanceCaptureContext(
            status = status,
            mode = mode,
            relayProtocolKind = sanitizeTransportKind(snapshot.relayTelemetry.protocolKind),
            relayListenerAddress = snapshot.relayTelemetry.listenerAddress?.trim(),
            serviceStartedAt = snapshot.serviceStartedAt,
            underlayObservation = underlayObservationProvider.capture(),
        )
    }

    private suspend fun captureRelayEvidence(context: AcceptanceCaptureContext): AcceptanceRelayEvidence {
        val endpoint = context.endpoint
        if (!context.serviceRunning || context.relayProtocolKind != RelayKindVlessReality || endpoint == null) {
            return AcceptanceRelayEvidence()
        }
        val families = context.underlayObservation.mandatoryRelayUdpPayloadFamilies()
        return coroutineScope {
            val connectivity =
                async {
                    probeOrNull(
                        endpoint,
                        RemoteAcceptanceConnectivityProbeUrl,
                        EgressRequirements(tcpConnect = true, udpAssociate = true),
                    )
                }
            val ipv4 = async { probeOrNull(endpoint, RemoteAcceptanceIpv4ProbeUrl, TcpOnlyRequirements) }
            val ipv6 = async { probeOrNull(endpoint, RemoteAcceptanceIpv6ProbeUrl, TcpOnlyRequirements) }
            val payloadHealth =
                async {
                    payloadHealthOrNull(
                        endpoint = endpoint,
                        families = families,
                        underlayGeneration = context.underlayObservation.generation,
                        serviceStartedAt = context.serviceStartedAt,
                    )
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
        underlayGeneration: Long?,
        serviceStartedAt: Long?,
    ): RelayUdpPayloadHealthEvidence? =
        if (underlayGeneration == null) {
            loadPayloadHealth(endpoint, families)
        } else {
            payloadHealthCache.getOrPut(
                key = RelayUdpPayloadHealthCacheKey(endpoint, underlayGeneration, serviceStartedAt, families),
                nowMs = monotonicClock(),
            ) {
                loadPayloadHealth(endpoint, families)
            }
        }

    private suspend fun loadPayloadHealth(
        endpoint: RelayProbeEndpoint,
        families: Set<RelayUdpPayloadFamily>,
    ): RelayUdpPayloadHealthEvidence? =
        try {
            relayCapabilityProbe.probePayloadHealth(endpoint, families)
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
)

private data class AcceptanceCaptureContext(
    val status: AppStatus,
    val mode: Mode?,
    val relayProtocolKind: String,
    val relayListenerAddress: String?,
    val serviceStartedAt: Long?,
    val underlayObservation: NetworkPathObservation,
) {
    val serviceRunning: Boolean
        get() = status == AppStatus.Running && mode == Mode.VPN

    val transportKind: String
        get() = relayProtocolKind

    val endpoint: RelayProbeEndpoint?
        get() = parseLocalRelayEndpoint(relayListenerAddress)

    fun driftError(after: AcceptanceCaptureContext): String? =
        ErrorPayloadHealthContextDrift.takeIf {
            status != after.status ||
                mode != after.mode ||
                relayProtocolKind != after.relayProtocolKind ||
                relayListenerAddress != after.relayListenerAddress ||
                serviceStartedAt != after.serviceStartedAt ||
                underlayObservation.generation != after.underlayObservation.generation
        }
}

internal data class RelayUdpPayloadHealthCacheKey(
    val endpoint: RelayProbeEndpoint,
    val underlayGeneration: Long?,
    val serviceStartedAt: Long?,
    val families: Set<RelayUdpPayloadFamily>,
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
        var leader = false
        val deferred =
            mutex.withLock {
                freshEntry(key, nowMs)?.let { cached ->
                    return cached.evidence
                }
                inFlight[key]
                    ?: CompletableDeferred<RelayUdpPayloadHealthEvidence?>()
                        .also { pending ->
                            inFlight[key] = pending
                            leader = true
                        }
            }
        if (!leader) return deferred.await()

        return try {
            val loaded = loader()
            mutex.withLock {
                entries[key] = CachedRelayUdpPayloadHealth(nowMs, loaded)
                trimLocked()
                inFlight.remove(key)
            }
            deferred.complete(loaded)
            loaded
        } catch (cancelled: CancellationException) {
            mutex.withLock { inFlight.remove(key) }
            deferred.completeExceptionally(cancelled)
            throw cancelled
        } catch (throwable: Throwable) {
            mutex.withLock { inFlight.remove(key) }
            deferred.completeExceptionally(throwable)
            throw throwable
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

internal const val RemoteAcceptanceConnectivityProbeUrl = "https://connectivitycheck.gstatic.com/generate_204"
internal const val RemoteAcceptanceIpv4ProbeUrl = "https://ipv4.google.com/generate_204"
internal const val RemoteAcceptanceIpv6ProbeUrl = "https://ipv6.google.com/generate_204"
private val TcpOnlyRequirements = EgressRequirements(tcpConnect = true, udpAssociate = false)
private const val MaxNetworkPort = 65_535
private const val MaxPayloadHealthCacheEntries = 16
private const val PayloadHealthCacheCooldownMs = 60_000L
