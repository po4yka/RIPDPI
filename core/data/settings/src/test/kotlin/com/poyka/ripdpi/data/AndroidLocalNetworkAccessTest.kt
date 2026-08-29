package com.poyka.ripdpi.data

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidLocalNetworkAccessTest {
    @Suppress("DEPRECATION")
    @Test
    fun `underlay selection prefers LAN capable transport when active network is VPN`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val connectivity = shadowOf(manager)
        connectivity.clearAllNetworks()
        connectivity.setActiveNetworkInfo(connectedNetworkInfo(ConnectivityManager.TYPE_VPN))
        val vpn = requireNotNull(manager.activeNetwork)
        connectivity.setNetworkCapabilities(vpn, capabilities(NetworkCapabilities.TRANSPORT_VPN))
        val cellular = ShadowNetwork.newInstance(201)
        val wifi = ShadowNetwork.newInstance(202)
        connectivity.addNetwork(cellular, connectedNetworkInfo(ConnectivityManager.TYPE_MOBILE))
        connectivity.setNetworkCapabilities(cellular, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))
        connectivity.addNetwork(wifi, connectedNetworkInfo(ConnectivityManager.TYPE_WIFI))
        connectivity.setNetworkCapabilities(wifi, capabilities(NetworkCapabilities.TRANSPORT_WIFI))

        assertEquals(wifi, manager.selectLocalNetworkUnderlay())
    }

    @Suppress("DEPRECATION")
    @Config(shadows = [UnresolvedHostnameNetworkShadow::class])
    @Test
    fun `unresolved ordinary hostname leaves DNS failure to the actual operation`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context).denyPermissions(LocalNetworkPermission)
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val connectivity = shadowOf(manager)
            connectivity.clearAllNetworks()
            connectivity.setActiveNetworkInfo(
                ShadowNetworkInfo.newInstance(
                    NetworkInfo.DetailedState.CONNECTED,
                    ConnectivityManager.TYPE_WIFI,
                    0,
                    true,
                    true,
                ),
            )
            connectivity.setDefaultNetworkActive(true)
            val network = requireNotNull(manager.activeNetwork)
            connectivity.setNetworkCapabilities(
                network,
                NetworkCapabilities().also { capabilities ->
                    NetworkCapabilities::class.java
                        .getDeclaredMethod("addTransportType", Int::class.javaPrimitiveType)
                        .invoke(capabilities, NetworkCapabilities.TRANSPORT_WIFI)
                    NetworkCapabilities::class.java
                        .getDeclaredMethod("addCapability", Int::class.javaPrimitiveType)
                        .invoke(capabilities, NetworkCapabilities.NET_CAPABILITY_INTERNET)
                },
            )
            val resolver = Shadow.extract<UnresolvedHostnameNetworkShadow>(network)

            val failure =
                runCatching {
                    AndroidLocalNetworkAccess(context).requireDirectEndpoint("unresolved.example", 443)
                }.exceptionOrNull()

            assertEquals(listOf("unresolved.example"), resolver.lookups)
            assertNull(failure)
        }

    @Test
    fun `direct local endpoint observes permission grant and revoke on the same access instance`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val permissions = shadowOf(context)
            permissions.denyPermissions(LocalNetworkPermission)
            val access = AndroidLocalNetworkAccess(context)

            permissions.grantPermissions(LocalNetworkPermission)
            val grantedFailure =
                runCatching { access.requireDirectEndpoint("fd12:3456::1", 443) }.exceptionOrNull()
            assertNull(grantedFailure)

            permissions.denyPermissions(LocalNetworkPermission)
            val revokedFailure =
                runCatching { access.requireDirectEndpoint("fd12:3456::1", 443) }.exceptionOrNull()

            assertEquals(
                FailureReason.PermissionLost(LocalNetworkPermission),
                (revokedFailure as? LocalNetworkAccessRequiredException)?.reason,
            )
        }

    @Test
    fun `direct local IPv6 endpoint requires local network permission on API 37`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context).denyPermissions(LocalNetworkPermission)
            val access = AndroidLocalNetworkAccess(context)

            val failure =
                runCatching {
                    access.requireDirectEndpoint("fd12:3456::1", 443)
                }.exceptionOrNull()

            assertEquals(
                FailureReason.PermissionLost(LocalNetworkPermission),
                (failure as? LocalNetworkAccessRequiredException)?.reason,
            )
        }

    @Suppress("DEPRECATION")
    private fun connectedNetworkInfo(type: Int) =
        ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            type,
            0,
            true,
            true,
        )

    private fun capabilities(transport: Int) =
        NetworkCapabilities().also { capabilities ->
            NetworkCapabilities::class.java
                .getDeclaredMethod("addTransportType", Int::class.javaPrimitiveType)
                .invoke(capabilities, transport)
            NetworkCapabilities::class.java
                .getDeclaredMethod("addCapability", Int::class.javaPrimitiveType)
                .invoke(capabilities, NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
}
