package com.poyka.ripdpi.services

import android.Manifest
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkHandoverBinderInitializationTest {
    @Test
    fun `own VPN callback invalidates binder token without changing physical network`() =
        verifyBinderInitialization(readyBeforeMonitor = true)

    @Test
    fun `physical callback acquires binder token without changing physical network`() =
        verifyBinderInitialization(readyBeforeMonitor = false)

    private fun verifyBinderInitialization(readyBeforeMonitor: Boolean) =
        runTest {
            val application = RuntimeEnvironment.getApplication()
            shadowOf(application).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
            val manager = application.getSystemService(ConnectivityManager::class.java)
            val connectivity = shadowOf(manager)
            val physical = checkNotNull(manager.activeNetwork)
            val capabilities = capabilities(vpn = false)
            val links = LinkProperties().also { it.setDnsServers(listOf(InetAddress.getByName("192.0.2.53"))) }
            connectivity.setNetworkCapabilities(physical, capabilities)
            connectivity.setLinkProperties(physical, links)
            val authority = DirectDnsUnderlayAuthority()
            val service = Robolectric.buildService(RipDpiVpnService::class.java).get()
            val binder = VpnUnderlyingNetworkBinder(service, authority)
            val callbacksBefore = connectivity.networkCallbacks.toSet()
            binder.start()
            val callback = (connectivity.networkCallbacks - callbacksBefore).single()
            val provider =
                AndroidNetworkFingerprintProvider(
                    DefaultAndroidNetworkSnapshotSource(application, authority),
                    NetworkFingerprintMapper(),
                )

            fun publishPhysical() {
                callback.onAvailable(physical)
                callback.onCapabilitiesChanged(physical, capabilities)
                callback.onLinkPropertiesChanged(physical, links)
            }
            if (readyBeforeMonitor) publishPhysical()
            val before = checkNotNull(provider.capture())
            val signals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val events = mutableListOf<NetworkHandoverEvent>()
            val job =
                backgroundScope.launch {
                    observeNetworkHandoverEvents(
                        signals,
                        provider::capture,
                        debounceMs = 2_000L,
                        clock = { testScheduler.currentTime },
                    ).toList(events)
                }
            try {
                runCurrent()
                if (readyBeforeMonitor) {
                    val ownVpn = ShadowNetwork.newInstance(126)
                    callback.onAvailable(ownVpn)
                    callback.onCapabilitiesChanged(
                        ownVpn,
                        capabilities(vpn = true),
                    )
                } else {
                    publishPhysical()
                }
                val after = checkNotNull(provider.capture())
                assertEquals(physical, manager.activeNetwork)
                assertEquals(
                    before.copy(directDnsUnderlayGeneration = null),
                    after.copy(directDnsUnderlayGeneration = null),
                )
                assertNotNull((if (readyBeforeMonitor) before else after).directDnsUnderlayGeneration)
                assertNull((if (readyBeforeMonitor) after else before).directDnsUnderlayGeneration)
                signals.emit(Unit)
                testScheduler.advanceTimeBy(2_000L)
                runCurrent()
                assertTrue("Binder initialization must not emit a physical handover", events.isEmpty())
            } finally {
                job.cancel()
                binder.stop()
            }
        }

    private fun capabilities(vpn: Boolean): NetworkCapabilities =
        NetworkCapabilities().also { value ->
            NetworkCapabilities::class.java
                .getDeclaredMethod("addTransportType", Int::class.javaPrimitiveType)
                .invoke(value, if (vpn) NetworkCapabilities.TRANSPORT_VPN else NetworkCapabilities.TRANSPORT_ETHERNET)
            listOf(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .forEach {
                    NetworkCapabilities::class.java
                        .getDeclaredMethod("addCapability", Int::class.javaPrimitiveType)
                        .invoke(value, it)
                }
            if (vpn) {
                NetworkCapabilities::class.java
                    .getDeclaredMethod("removeCapability", Int::class.javaPrimitiveType)
                    .invoke(value, NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
        }
}
