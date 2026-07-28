package com.poyka.ripdpi.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.URI
import javax.inject.Inject

internal class RemoteDeviceAcceptanceBaselineProbe
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val serviceStateStore: ServiceStateStore,
        private val relayCapabilityProbe: RelayCapabilityProbe,
    ) {
        private val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private val networkStatePermitted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_NETWORK_STATE) ==
                PackageManager.PERMISSION_GRANTED

        /** cancel-safe: every network operation is bounded and propagates cancellation. */
        suspend fun capture(snapshot: ServiceTelemetrySnapshot): RemoteDeviceAcceptanceReport {
            val startedAt = SystemClock.elapsedRealtime()
            val running =
                serviceStateStore.status.value.let { (status, mode) ->
                    status == AppStatus.Running && mode == Mode.VPN
                }
            val transportKind = sanitizeTransportKind(snapshot.relayTelemetry.protocolKind)
            val endpoint = parseLocalRelayEndpoint(snapshot.relayTelemetry.listenerAddress)
            val probeEvidence = captureRelayEvidence(running, transportKind, endpoint)
            val routeFamilies = captureVpnRouteFamilies()
            return buildRemoteDeviceAcceptanceBaseline(
                device = captureRemoteDeviceAcceptanceDevice(),
                evidence =
                    AcceptanceBaselineEvidence(
                        serviceRunning = running,
                        transportKind = transportKind,
                        listenerAvailable = endpoint != null,
                        probe = probeEvidence,
                        ipv4Route = routeFamilies.first,
                        ipv6Route = routeFamilies.second,
                        directEgressObserved = snapshot.relayFailed,
                        durationMs = elapsedSince(startedAt),
                    ),
            )
        }

        private suspend fun captureRelayEvidence(
            running: Boolean,
            transportKind: String,
            endpoint: RelayProbeEndpoint?,
        ): RelayCapabilityProbeEvidence? {
            if (!running || transportKind != RelayKindVlessReality || endpoint == null) return null
            return try {
                relayCapabilityProbe.probeEvidence(
                    endpoint = endpoint,
                    url = AcceptanceProbeUrl,
                    requirements = EgressRequirements(tcpConnect = true, udpAssociate = true),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

        private fun captureVpnRouteFamilies(): Pair<Boolean, Boolean> {
            val linkProperties =
                connectivityManager.activeNetwork
                    ?.takeIf { networkStatePermitted }
                    ?.takeIf { network ->
                        connectivityManager
                            .getNetworkCapabilities(network)
                            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                    }?.let(connectivityManager::getLinkProperties)
            val routes = linkProperties?.routes.orEmpty()
            val ipv4 = routes.any { it.isDefaultRoute && it.destination.address is Inet4Address }
            val ipv6 = routes.any { it.isDefaultRoute && it.destination.address is Inet6Address }
            return ipv4 to ipv6
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

private fun elapsedSince(startedAt: Long): Long = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)

private const val AcceptanceProbeUrl = "https://connectivitycheck.gstatic.com/generate_204"
private const val MaxNetworkPort = 65_535
