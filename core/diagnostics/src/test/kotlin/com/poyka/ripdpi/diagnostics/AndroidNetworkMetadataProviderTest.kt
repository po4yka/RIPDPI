@file:Suppress("DEPRECATION") // Robolectric's NetworkInfo controls the calling UID's default network.

package com.poyka.ripdpi.diagnostics

import android.Manifest
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.RouteInfo
import com.poyka.ripdpi.data.AuthoritativeVpnUnderlayObservationProvider
import com.poyka.ripdpi.data.NetworkMetadataCaptureScope
import com.poyka.ripdpi.data.NetworkPathAssociationServiceBinder
import com.poyka.ripdpi.data.NetworkPathObservation
import com.poyka.ripdpi.data.VpnRouteEvidence
import com.poyka.ripdpi.data.VpnRouteEvidenceProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidNetworkMetadataProviderTest {
    private val application = RuntimeEnvironment.getApplication()
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val shadow = shadowOf(connectivity)
    private val epoch = FakeDiagnosticsNetworkEpochProvider()
    private val underlay =
        object : AuthoritativeVpnUnderlayObservationProvider {
            override val changes = MutableStateFlow(3L)

            override fun capture() =
                NetworkPathObservation(
                    association = NetworkPathAssociationServiceBinder,
                    transport = "wifi",
                    validated = true,
                    generation = changes.value,
                )
        }
    private val vpnRoute =
        object : VpnRouteEvidenceProvider {
            override val changes = MutableStateFlow(7L)

            override fun capture() = VpnRouteEvidence(callbackRevision = changes.value)
        }

    @Test
    fun `stable capture preserves the observed network metadata`() =
        runTest {
            val snapshot = capture()
            assertEquals("wifi", snapshot.transport)
            assertEquals(1500, snapshot.mtu)
            assertEquals("off", snapshot.privateDnsMode)
            assertEquals(listOf("redacted"), snapshot.dnsServers)
            assertEquals("198.51.100.1", snapshot.publicIp)
            assertEquals("AS64500", snapshot.publicAsn)
            assertEquals("captured", snapshot.pathValidation?.captureStatus)
            assertEquals(true, snapshot.pathValidation?.underlayValidated)
            assertEquals(true, snapshot.networkValidated)
            assertNotNull(snapshot.pathSnapshots)
        }

    @Test
    fun `missing link properties leave Private DNS unknown`() =
        runTest {
            val snapshot = capture(hasLinks = false)
            assertEquals("captured", snapshot.pathValidation?.captureStatus)
            assertEquals("unknown", snapshot.privateDnsMode)
            assertEquals(SystemPrivateDnsStatus.UNKNOWN.wireValue, snapshot.systemPrivateDnsStatus)
        }

    @Test
    fun `Wi-Fi to LTE and back invalidates the original capture`() =
        runTest {
            assertUnavailable(
                capture {
                    selectNetwork(ConnectivityManager.TYPE_MOBILE, NetworkCapabilities.TRANSPORT_CELLULAR)
                    epoch.epoch = FakeDiagnosticsNetworkEpoch(2)
                    selectNetwork(ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
                    epoch.epoch = FakeDiagnosticsNetworkEpoch(3)
                },
            )
        }

    @Test
    fun `switching to LTE during the lookup discards metadata`() =
        runTest {
            assertUnavailable(
                capture {
                    selectNetwork(ConnectivityManager.TYPE_MOBILE, NetworkCapabilities.TRANSPORT_CELLULAR)
                    epoch.epoch = FakeDiagnosticsNetworkEpoch(2)
                },
            )
        }

    @Test
    fun `a new default network invalidates capture even with the same physical epoch`() =
        runTest {
            assertUnavailable(
                capture {
                    selectNetwork(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
                },
            )
        }

    @Test
    fun `default route can change and return without a physical handover`() =
        runTest {
            assertUnavailable(
                capture { callback ->
                    selectNetwork(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
                    callback.onAvailable(requireNotNull(connectivity.activeNetwork))
                    selectNetwork(ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
                    callback.onAvailable(requireNotNull(connectivity.activeNetwork))
                },
            )
        }

    @Test
    fun `registration replay of the current route keeps the capture`() =
        runTest {
            val snapshot =
                capture { callback ->
                    val network = requireNotNull(connectivity.activeNetwork)
                    callback.onAvailable(network)
                    callback.onLinkPropertiesChanged(network, requireNotNull(connectivity.getLinkProperties(network)))
                    callback.onBlockedStatusChanged(network, false)
                }
            assertEquals("wifi", snapshot.transport)
        }

    @Test
    fun `blocked status change after the replay invalidates capture`() =
        runTest {
            assertUnavailable(
                capture { callback ->
                    val network = requireNotNull(connectivity.activeNetwork)
                    callback.onBlockedStatusChanged(network, false)
                    callback.onBlockedStatusChanged(network, true)
                },
            )
        }

    @Test
    fun `a new route on the same default network invalidates capture`() =
        runTest {
            assertUnavailable(
                capture {
                    shadow.setLinkProperties(connectivity.activeNetwork, links("192.0.2.2"))
                },
            )
        }

    @Test
    fun `VPN route and underlay changes each invalidate capture`() =
        runTest {
            assertUnavailable(capture { vpnRoute.changes.value += 1 })
            assertUnavailable(capture { underlay.changes.value += 1 })
        }

    @Test
    fun `missing physical epoch skips the public IP lookup`() =
        runTest {
            epoch.epoch = null
            assertUnavailable(capture { error("Public IP lookup ran without a verified capture scope") })
        }

    private suspend fun capture(
        hasLinks: Boolean = true,
        duringLookup: (ConnectivityManager.NetworkCallback) -> Unit = {},
    ): NetworkSnapshotModel {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        selectNetwork(ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
        if (!hasLinks) shadow.setLinkProperties(connectivity.activeNetwork, null)
        val existingCallbacks = shadow.networkCallbacks.toSet()
        val scope = NetworkMetadataCaptureScope(application, epoch, underlay, vpnRoute)
        val callback = (shadow.networkCallbacks - existingCallbacks).single()
        val resolver =
            object : PublicIpInfoResolver {
                override suspend fun resolve(): PublicIpInfo {
                    yield()
                    duringLookup(callback)
                    return PublicIpInfo(ip = "198.51.100.1", asn = "AS64500")
                }
            }
        return AndroidNetworkMetadataProvider(application, resolver, underlay, vpnRoute, scope)
            .captureSnapshot(includePublicIp = true)
    }

    private fun selectNetwork(
        type: Int,
        transport: Int,
    ) {
        shadow.setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, true),
        )
        val network = requireNotNull(connectivity.activeNetwork)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(transport)
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadow.setNetworkCapabilities(network, capabilities)
        shadow.setLinkProperties(network, links("192.0.2.1"))
    }

    private fun links(gateway: String): LinkProperties =
        LinkProperties().apply {
            interfaceName = "test0"
            mtu = 1500
            setDnsServers(listOf(InetAddress.getByName("192.0.2.53")))
            addRoute(
                ReflectionHelpers.callConstructor(
                    RouteInfo::class.java,
                    ClassParameter.from(IpPrefix::class.java, IpPrefix(InetAddress.getByName("0.0.0.0"), 0)),
                    ClassParameter.from(InetAddress::class.java, InetAddress.getByName(gateway)),
                    ClassParameter.from(String::class.java, interfaceName),
                ),
            )
        }

    private fun assertUnavailable(snapshot: NetworkSnapshotModel) {
        assertEquals("unavailable", snapshot.transport)
        assertEquals("unavailable", snapshot.pathValidation?.captureStatus)
        assertEquals(emptyList<String>(), snapshot.capabilities)
        assertEquals(emptyList<String>(), snapshot.dnsServers)
        assertEquals(emptyList<String>(), snapshot.localAddresses)
        assertNull(snapshot.mtu)
        assertNull(snapshot.publicIp)
        assertNull(snapshot.publicAsn)
        assertNull(snapshot.wifiDetails)
        assertNull(snapshot.cellularDetails)
        assertNull(snapshot.pathSnapshots)
        assertNull(snapshot.pathValidation?.vpnRouteEvidence)
        assertFalse(snapshot.hasObservedNetworkCapabilities)
    }
}
