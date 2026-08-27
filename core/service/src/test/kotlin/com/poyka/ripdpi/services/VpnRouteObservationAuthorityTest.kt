package com.poyka.ripdpi.services

import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Build
import android.os.Process
import com.poyka.ripdpi.data.VpnRouteCallbackState
import com.poyka.ripdpi.data.VpnRouteFamilyIpv4
import com.poyka.ripdpi.data.VpnRouteOwnerVerification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VpnRouteObservationAuthorityTest {
    @Test
    fun `registered callbacks complete owned vpn evidence without synchronous network state`() {
        listOf(false, true).forEach { routesFirst ->
            val store = VpnRouteLifecycleReceiptStore()
            store.markEstablished(store.beginTestGeneration())
            val authority = testAuthority(store)
            authority.start()
            val callback = requireNotNull(authority.registeredCallbackForTest())
            val network = ShadowNetwork.newInstance(101)
            val capabilities = ownedVpnCapabilities()
            val linkProperties = ipv4DefaultLinkProperties()
            val connectivityManager =
                RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
            assertNull(connectivityManager.getNetworkCapabilities(network))
            assertNull(connectivityManager.getLinkProperties(network))

            try {
                callback.onAvailable(network)
                if (routesFirst) {
                    callback.onLinkPropertiesChanged(network, linkProperties)
                    callback.onCapabilitiesChanged(network, capabilities)
                } else {
                    callback.onCapabilitiesChanged(network, capabilities)
                    callback.onLinkPropertiesChanged(network, linkProperties)
                }

                val evidence = store.capture()
                assertEquals(
                    "routesFirst=$routesFirst",
                    VpnRouteCallbackState.Complete to VpnRouteOwnerVerification.Verified,
                    evidence.callbackState to evidence.ownerVerification,
                )
                assertEquals(listOf(VpnRouteFamilyIpv4), evidence.observedDefaultRouteFamilies)
            } finally {
                authority.stop()
            }
        }
    }

    @Test
    fun `stale synchronous getters cannot publish or discard callback evidence`() {
        val store = VpnRouteLifecycleReceiptStore()
        store.markEstablished(store.beginTestGeneration())
        val authority = testAuthority(store)
        authority.start()
        val callback = requireNotNull(authority.registeredCallbackForTest())
        val network = ShadowNetwork.newInstance(102)
        val connectivityManager =
            RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivityManager).setLinkProperties(network, LinkProperties())
        shadowOf(connectivityManager).setNetworkCapabilities(network, NetworkCapabilities())

        try {
            callback.onAvailable(network)
            callback.onCapabilitiesChanged(network, ownedVpnCapabilities())
            val capabilitiesOnly = store.capture().callbackState
            callback.onLinkPropertiesChanged(network, ipv4DefaultLinkProperties())

            assertEquals(
                listOf(VpnRouteCallbackState.Awaiting, VpnRouteCallbackState.Complete),
                listOf(capabilitiesOnly, store.capture().callbackState),
            )
        } finally {
            authority.stop()
        }
    }

    @Test
    @Config(sdk = [29])
    fun `callback from stopped registration cannot revive route evidence`() {
        val store = VpnRouteLifecycleReceiptStore()
        val generation = store.beginTestGeneration()
        store.markEstablished(generation)
        val authority = testAuthority(store)
        authority.start()
        val callback = authority.registeredCallbackForTest()
        assertNotNull(callback)

        authority.stop()
        val network = ShadowNetwork.newInstance(97)
        callback?.onCapabilitiesChanged(network, vpnCapabilities())
        callback?.onLinkPropertiesChanged(network, ipv4DefaultLinkProperties())

        assertEquals(VpnRouteCallbackState.Unavailable, store.capture().callbackState)
    }

    @Test
    fun `disqualified vpn callback is not later reported as owned loss`() {
        val store = VpnRouteLifecycleReceiptStore()
        val generation = store.beginTestGeneration()
        store.markEstablished(generation)
        val authority = testAuthority(store)
        val network = ShadowNetwork.newInstance(98)
        authority.onCapabilitiesChanged(network, ownedVpnCapabilities())
        authority.onLinkPropertiesChanged(network, ipv4DefaultLinkProperties())

        authority.onCapabilitiesChanged(network, ownedVpnCapabilities(ownerUid = Process.myUid() + 1))
        authority.onLost(network)

        assertEquals(VpnRouteCallbackState.Awaiting, store.capture().callbackState)
        assertEquals(null, store.capture().vpnPresent)
    }

    @Test
    fun `registered foreign vpn callbacks cannot mask owned vpn loss`() {
        val store = VpnRouteLifecycleReceiptStore()
        store.markEstablished(store.beginTestGeneration())
        val authority = testAuthority(store)
        authority.start()
        val callback = requireNotNull(authority.registeredCallbackForTest())
        val ownedNetwork = ShadowNetwork.newInstance(105)
        val foreignNetwork = ShadowNetwork.newInstance(106)

        try {
            callback.onAvailable(ownedNetwork)
            callback.onCapabilitiesChanged(ownedNetwork, ownedVpnCapabilities())
            callback.onLinkPropertiesChanged(ownedNetwork, ipv4DefaultLinkProperties())
            assertEquals(VpnRouteCallbackState.Complete, store.capture().callbackState)

            callback.onAvailable(foreignNetwork)
            callback.onCapabilitiesChanged(foreignNetwork, ownedVpnCapabilities(ownerUid = Process.myUid() + 1))
            callback.onLinkPropertiesChanged(foreignNetwork, ipv4DefaultLinkProperties())
            callback.onLost(ownedNetwork)

            assertEquals(VpnRouteCallbackState.Lost, store.capture().callbackState)
        } finally {
            authority.stop()
        }
    }

    @Test
    @Config(sdk = [29])
    fun `pre owner uid callback is retained when it arrives during establish`() {
        val store = VpnRouteLifecycleReceiptStore()
        val generation = store.beginTestGeneration()
        val authority = testAuthority(store)
        val network = ShadowNetwork.newInstance(99)

        authority.onCapabilitiesChanged(network, vpnCapabilities())
        authority.onLinkPropertiesChanged(network, ipv4DefaultLinkProperties())
        store.markEstablished(generation)

        assertEquals(VpnRouteCallbackState.Complete, store.capture().callbackState)
        assertEquals(VpnRouteOwnerVerification.Unavailable, store.capture().ownerVerification)
    }

    @Test
    fun `owned vpn request removes default NOT VPN capability`() {
        val request = ownedVpnNetworkRequest(Build.VERSION.SDK_INT)

        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
    }

    @Test
    @Config(sdk = [29])
    fun `pre owner uid callback correlates only while current receipt is live`() {
        val store = VpnRouteLifecycleReceiptStore()
        val generation =
            store.beginIntended(
                ipv6Enabled = false,
                dns = "1.1.1.1",
                appRoutingPlan = VpnAppRoutingPlan.Disallow(setOf("com.poyka.ripdpi")),
                ownPackage = "com.poyka.ripdpi",
                networkParameters = VpnTunnelNetworkParameters(),
                apiLevel = Build.VERSION_CODES.Q,
            )
        store.markEstablished(generation)
        val authority = testAuthority(store)
        val network = ShadowNetwork.newInstance(100)

        authority.onCapabilitiesChanged(network, vpnCapabilities())
        authority.onLinkPropertiesChanged(network, ipv4DefaultLinkProperties())

        val evidence = store.capture()
        assertEquals(VpnRouteCallbackState.Complete, evidence.callbackState)
        assertEquals(VpnRouteOwnerVerification.Unavailable, evidence.ownerVerification)
        assertEquals(true, evidence.validated)
    }
}

private fun testAuthority(store: VpnRouteLifecycleReceiptStore): VpnRouteObservationAuthority =
    VpnRouteObservationAuthority(
        context = RuntimeEnvironment.getApplication(),
        receiptStore = store,
    )

private fun VpnRouteObservationAuthority.registeredCallbackForTest(): ConnectivityManager.NetworkCallback? {
    val field = VpnRouteObservationAuthority::class.java.getDeclaredField("registeredCallback")
    field.isAccessible = true
    return field.get(this) as? ConnectivityManager.NetworkCallback
}

private fun VpnRouteLifecycleReceiptStore.beginTestGeneration(): Long =
    beginIntended(
        ipv6Enabled = false,
        dns = "1.1.1.1",
        appRoutingPlan = VpnAppRoutingPlan.Disallow(setOf("com.poyka.ripdpi")),
        ownPackage = "com.poyka.ripdpi",
        networkParameters = VpnTunnelNetworkParameters(),
        apiLevel = Build.VERSION_CODES.Q,
    )

private fun vpnCapabilities(): NetworkCapabilities =
    NetworkCapabilities().also { capabilities ->
        listOf(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_VALIDATED,
        ).forEach { capability ->
            NetworkCapabilities::class
                .java
                .getDeclaredMethod("addCapability", Int::class.javaPrimitiveType)
                .invoke(capabilities, capability)
        }
        NetworkCapabilities::class
            .java
            .getDeclaredMethod("addTransportType", Int::class.javaPrimitiveType)
            .invoke(capabilities, NetworkCapabilities.TRANSPORT_VPN)
        NetworkCapabilities::class
            .java
            .getDeclaredMethod("removeCapability", Int::class.javaPrimitiveType)
            .invoke(capabilities, NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

private fun ownedVpnCapabilities(ownerUid: Int = Process.myUid()): NetworkCapabilities =
    vpnCapabilities().also { capabilities ->
        NetworkCapabilities::class
            .java
            .getDeclaredMethod("setOwnerUid", Int::class.javaPrimitiveType)
            .invoke(capabilities, ownerUid)
    }

private fun ipv4DefaultLinkProperties(): LinkProperties {
    val interfaceName = "test0"
    val route =
        RouteInfo::class
            .java
            .getDeclaredConstructor(IpPrefix::class.java, InetAddress::class.java, String::class.java)
            .newInstance(
                IpPrefix(InetAddress.getByName("0.0.0.0"), 0),
                InetAddress.getByName("192.0.2.1"),
                interfaceName,
            )
    return LinkProperties().apply {
        this.interfaceName = interfaceName
        addRoute(route)
    }
}
