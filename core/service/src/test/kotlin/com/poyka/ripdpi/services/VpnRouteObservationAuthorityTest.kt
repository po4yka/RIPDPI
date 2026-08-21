package com.poyka.ripdpi.services

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.poyka.ripdpi.data.VpnRouteCallbackState
import com.poyka.ripdpi.data.VpnRouteFamilyIpv4
import com.poyka.ripdpi.data.VpnRouteOwnerVerification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VpnRouteObservationAuthorityTest {
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
        callback?.onCapabilitiesChanged(ShadowNetwork.newInstance(97), vpnCapabilities())

        assertEquals(VpnRouteCallbackState.Unavailable, store.capture().callbackState)
    }

    @Test
    @Config(sdk = [29])
    fun `disqualified vpn callback is not later reported as owned loss`() {
        val store = VpnRouteLifecycleReceiptStore()
        val generation = store.beginTestGeneration()
        store.markEstablished(generation)
        val authority = testAuthority(store)
        val network = ShadowNetwork.newInstance(98)
        authority.onCapabilitiesChanged(network, vpnCapabilities())
        store.observeDefaultRoutes(network, setOf(VpnRouteFamilyIpv4))

        authority.onCapabilitiesChanged(network, NetworkCapabilities())
        authority.onLost(network)

        assertEquals(VpnRouteCallbackState.Awaiting, store.capture().callbackState)
        assertEquals(null, store.capture().vpnPresent)
    }

    @Test
    @Config(sdk = [29])
    fun `pre owner uid callback is retained when it arrives during establish`() {
        val store = VpnRouteLifecycleReceiptStore()
        val generation = store.beginTestGeneration()
        val authority = testAuthority(store)
        val network = ShadowNetwork.newInstance(99)

        authority.onCapabilitiesChanged(network, vpnCapabilities())
        store.observePreOwnerNetworkShape(network)
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
        store.observePreOwnerNetworkShape(network)

        val evidence = store.capture()
        assertEquals(VpnRouteCallbackState.Complete, evidence.callbackState)
        assertEquals(VpnRouteOwnerVerification.Unavailable, evidence.ownerVerification)
        assertEquals(true, evidence.validated)
    }
}

private fun VpnRouteLifecycleReceiptStore.observePreOwnerNetworkShape(network: Any) {
    observeNetworkShape(
        networkKey = network,
        hasInternet = true,
        validated = true,
        captivePortal = false,
        ownerVerification = VpnRouteOwnerVerification.Unavailable,
        families = setOf(VpnRouteFamilyIpv4),
    )
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
