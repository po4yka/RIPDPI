package com.poyka.ripdpi.services

import android.os.SystemClock
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.InetSocketAddress
import java.net.URI
import javax.inject.Inject

internal class RemoteDeviceAcceptanceBaselineProbe internal constructor(
    private val serviceStateStore: ServiceStateStore,
    private val relayCapabilityProbe: RelayCapabilityProbe,
    private val deviceProvider: () -> RemoteDeviceAcceptanceDevice,
) {
    @Inject
    constructor(
        serviceStateStore: ServiceStateStore,
        relayCapabilityProbe: RelayCapabilityProbe,
    ) : this(serviceStateStore, relayCapabilityProbe, ::captureRemoteDeviceAcceptanceDevice)

    /**
     * Uses only the local relay listener, so own-app VPN exclusion cannot bypass the tested egress.
     * cancel-safe: every network operation is bounded and propagates cancellation.
     */
    suspend fun capture(snapshot: ServiceTelemetrySnapshot): RemoteDeviceAcceptanceReport {
        val startedAt = SystemClock.elapsedRealtime()
        val running =
            serviceStateStore.status.value.let { (status, mode) ->
                status == AppStatus.Running && mode == Mode.VPN
            }
        val transportKind = sanitizeTransportKind(snapshot.relayTelemetry.protocolKind)
        val endpoint = parseLocalRelayEndpoint(snapshot.relayTelemetry.listenerAddress)
        val probeEvidence = captureRelayEvidence(running, transportKind, endpoint)
        return buildRemoteDeviceAcceptanceBaseline(
            device = deviceProvider(),
            evidence =
                AcceptanceBaselineEvidence(
                    serviceRunning = running,
                    transportKind = transportKind,
                    listenerAvailable = endpoint != null,
                    probe = probeEvidence.connectivity,
                    ipv4Probe = probeEvidence.ipv4,
                    ipv6Probe = probeEvidence.ipv6,
                    directEgressObserved = snapshot.relayFailed,
                    durationMs = elapsedSince(startedAt),
                ),
        )
    }

    private suspend fun captureRelayEvidence(
        running: Boolean,
        transportKind: String,
        endpoint: RelayProbeEndpoint?,
    ): AcceptanceRelayEvidence {
        if (!running || transportKind != RelayKindVlessReality || endpoint == null) {
            return AcceptanceRelayEvidence()
        }
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
            AcceptanceRelayEvidence(connectivity.await(), ipv4.await(), ipv6.await())
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
}

private data class AcceptanceRelayEvidence(
    val connectivity: RelayCapabilityProbeEvidence? = null,
    val ipv4: RelayCapabilityProbeEvidence? = null,
    val ipv6: RelayCapabilityProbeEvidence? = null,
)

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

private fun elapsedSince(startedAt: Long): Long = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)

internal const val RemoteAcceptanceConnectivityProbeUrl = "https://connectivitycheck.gstatic.com/generate_204"
internal const val RemoteAcceptanceIpv4ProbeUrl = "https://ipv4.google.com/generate_204"
internal const val RemoteAcceptanceIpv6ProbeUrl = "https://ipv6.google.com/generate_204"
private val TcpOnlyRequirements = EgressRequirements(tcpConnect = true, udpAssociate = false)
private const val MaxNetworkPort = 65_535
