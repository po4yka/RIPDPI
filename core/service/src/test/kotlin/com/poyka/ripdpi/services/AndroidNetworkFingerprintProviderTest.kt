package com.poyka.ripdpi.services

import android.Manifest
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Build
import com.poyka.ripdpi.data.NetworkFingerprint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
class AndroidNetworkFingerprintProviderTest {
    private val mapper = NetworkFingerprintMapper()

    @Test
    fun `VPN default does not replace selected physical fingerprint`() {
        val fixture = physicalAndVpnNetworks()
        val source = source(fixture)
        val provider = AndroidNetworkFingerprintProvider(source, mapper)

        val before = checkNotNull(provider.capture())
        assertEquals("wifi", before.transport)
        assertEquals(listOf("192.0.2.53"), before.dnsServers)
        fixture.overlayCapabilities.addTestTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        fixture.overlayLinks.setDnsServers(listOf(InetAddress.getByName("192.0.2.99")))

        assertEquals(before, provider.capture())
        assertNull(classifyNetworkHandover(before, provider.capture()))
    }

    @Test
    fun `tunnel parameters subtract overhead from physical MTU only once`() {
        val fixture = physicalAndVpnNetworks()
        val service = Robolectric.buildService(RipDpiVpnService::class.java).get()
        service.physicalNetworkObserver = fixture.observer

        val parameters = service.currentTunnelNetworkParameters()

        assertEquals(1_420, parameters.tunnelMtu)
        assertEquals(80, parameters.appliedEncapsulationBudgetBytes)
        assertEquals(false, parameters.metered)
    }

    private class PhysicalAndVpnNetworks(
        val observer: PhysicalNetworkObserver,
        val overlayCapabilities: NetworkCapabilities,
        val overlayLinks: LinkProperties,
        val callback: ConnectivityManager.NetworkCallback,
        val physical: Network,
        val capabilities: NetworkCapabilities,
        val links: LinkProperties,
    )

    private fun physicalAndVpnNetworks(ready: Boolean = true): PhysicalAndVpnNetworks {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val connectivity = shadowOf(manager)
        val overlay = checkNotNull(manager.activeNetwork)
        val overlayCapabilities = NetworkCapabilities().addTestTransport(NetworkCapabilities.TRANSPORT_VPN)
        val overlayLinks = LinkProperties().apply { mtu = 1_420 }
        connectivity.setNetworkCapabilities(overlay, overlayCapabilities)
        connectivity.setLinkProperties(overlay, overlayLinks)
        val before = connectivity.networkCallbacks.toSet()
        val observer = PhysicalNetworkObserver(context)
        val callback = (connectivity.networkCallbacks - before).single()
        val physical = ShadowNetwork.newInstance(42)
        val capabilities = physicalNetworkTestCapabilities()
        val links = physicalNetworkTestLinks()
        if (ready) {
            callback.onAvailable(physical)
            callback.onCapabilitiesChanged(physical, capabilities)
            callback.onLinkPropertiesChanged(physical, links)
            if (Build.VERSION.SDK_INT >= 29) callback.onBlockedStatusChanged(physical, false)
        }
        return PhysicalAndVpnNetworks(
            observer,
            overlayCapabilities,
            overlayLinks,
            callback,
            physical,
            capabilities,
            links,
        )
    }

    private fun source(fixture: PhysicalAndVpnNetworks) =
        DefaultAndroidNetworkSnapshotSource(
            RuntimeEnvironment.getApplication(),
            DirectDnsUnderlayAuthority(),
            fixture.observer,
        )

    @Test
    fun `physical snapshots own copies of callback and returned payloads`() {
        val fixture = physicalAndVpnNetworks()
        val first = checkNotNull(fixture.observer.selectedPhysicalSnapshot())
        fixture.capabilities.addTestTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        fixture.links.mtu = 1_300
        first.capabilities.addTestTransport(NetworkCapabilities.TRANSPORT_VPN)
        first.links.setDnsServers(emptyList())
        first.links.mtu = 1_200

        val next = checkNotNull(fixture.observer.selectedPhysicalSnapshot())
        assertEquals(1_500, next.links.mtu)
        assertEquals(listOf("192.0.2.53"), next.links.dnsServers.map { it.hostAddress })
        assertEquals(false, next.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        assertEquals(false, next.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        assertEquals(first.semanticSignature, next.semanticSignature)
        assertEquals("PhysicalNetworkSnapshot([REDACTED])", next.toString())
    }

    @Test
    fun `VPN only incomplete blocked and lost paths do not supply physical fingerprints`() {
        val fixture = physicalAndVpnNetworks(ready = false)
        val source = source(fixture)
        assertNull(source.capture())
        fixture.callback.onAvailable(fixture.physical)
        fixture.callback.onCapabilitiesChanged(fixture.physical, fixture.capabilities)
        assertNull(source.capture())
        fixture.callback.onLinkPropertiesChanged(fixture.physical, fixture.links)
        assertNull(source.capture())
        fixture.callback.onBlockedStatusChanged(fixture.physical, true)
        assertNull(source.capture())
        fixture.callback.onBlockedStatusChanged(fixture.physical, false)
        assertNotNull(source.capture())
        fixture.callback.onLost(fixture.physical)
        assertNull(source.capture())
        val service = Robolectric.buildService(RipDpiVpnService::class.java).get()
        service.physicalNetworkObserver = fixture.observer
        assertEquals(VpnTunnelNetworkPolicy.parameters(null, null), service.currentTunnelNetworkParameters())
    }

    @Test
    @Config(sdk = [28])
    fun `legacy multiple candidates are unavailable until only one remains`() {
        val fixture = physicalAndVpnNetworks()
        val source = source(fixture)
        assertNotNull(source.capture())
        val other = ShadowNetwork.newInstance(43)
        fixture.callback.onAvailable(other)
        assertNull(source.capture())
        fixture.callback.onCapabilitiesChanged(other, physicalNetworkTestCapabilities())
        fixture.callback.onLinkPropertiesChanged(other, physicalNetworkTestLinks())
        assertNull(source.capture())
        fixture.callback.onLost(other)
        assertNotNull(source.capture())
    }

    @Test
    fun `physical changes emit once while overlay and nonselected callbacks do not`() =
        runTest {
            val fixture = physicalAndVpnNetworks()
            val provider = AndroidNetworkFingerprintProvider(source(fixture), mapper)
            val events = mutableListOf<NetworkHandoverEvent>()
            backgroundScope.launch {
                observeNetworkHandoverEvents(
                    fixture.observer.changes.map { Unit },
                    provider::capture,
                    0L,
                    { testScheduler.currentTime },
                ).toList(events)
            }
            runCurrent()
            fixture.overlayCapabilities.addTestTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            fixture.callback.onCapabilitiesChanged(fixture.physical, fixture.capabilities)
            runCurrent()
            assertTrue(events.isEmpty())

            val cellular = ShadowNetwork.newInstance(44)
            fixture.callback.onAvailable(cellular)
            fixture.callback.onCapabilitiesChanged(
                cellular,
                physicalNetworkTestCapabilities(NetworkCapabilities.TRANSPORT_CELLULAR),
            )
            fixture.callback.onLinkPropertiesChanged(cellular, physicalNetworkTestLinks())
            fixture.callback.onBlockedStatusChanged(cellular, false)
            runCurrent()
            assertEquals(listOf("transport_switch"), events.map { it.classification })
            fixture.callback.onCapabilitiesChanged(fixture.physical, fixture.capabilities)
            fixture.callback.onLost(fixture.physical)
            runCurrent()
            assertEquals(1, events.size)
            fixture.callback.onLinkPropertiesChanged(cellular, physicalNetworkTestLinks())
            runCurrent()
            assertEquals(1, events.size)
            fixture.callback.onLinkPropertiesChanged(cellular, physicalNetworkTestLinks().apply { mtu = 1_400 })
            runCurrent()
            assertEquals(listOf("transport_switch", "link_refresh"), events.map { it.classification })
        }

    @Test
    fun `DNS private DNS route and MTU changes remain physical refreshes without a binder token`() {
        val fixture = physicalAndVpnNetworks()
        val provider = AndroidNetworkFingerprintProvider(source(fixture), mapper)
        val initial = checkNotNull(provider.capture())
        assertNull(initial.directDnsUnderlayGeneration)
        val changes =
            listOf<(LinkProperties) -> Unit>(
                { it.setDnsServers(listOf(InetAddress.getByName("192.0.2.54"))) },
                { it.mtu = 1_400 },
                { it.addRoute(physicalNetworkTestRoute("192.0.2.9")) },
                {
                    LinkProperties::class.java
                        .getDeclaredMethod(
                            "setUsePrivateDns",
                            Boolean::class.javaPrimitiveType,
                        ).invoke(it, true)
                },
                {
                    LinkProperties::class.java
                        .getDeclaredMethod(
                            "setPrivateDnsServerName",
                            String::class.java,
                        ).invoke(it, "dns.example")
                },
            )
        changes.forEach { change ->
            val links = physicalNetworkTestLinks().also(change)
            fixture.callback.onLinkPropertiesChanged(fixture.physical, links)
            val changed = checkNotNull(provider.capture())
            assertEquals("link_refresh", classifyNetworkHandover(initial, changed))
            assertNotEquals(initial.physicalNetworkSignature, changed.physicalNetworkSignature)
            assertNull(changed.directDnsUnderlayGeneration)
        }
    }

    @Test
    fun `same transport network replacement cannot be masked by absent binder tokens`() {
        val fixture = physicalAndVpnNetworks()
        val provider = AndroidNetworkFingerprintProvider(source(fixture), mapper)
        val before = checkNotNull(provider.capture())
        val replacement = ShadowNetwork.newInstance(45)
        fixture.callback.onAvailable(replacement)
        fixture.callback.onCapabilitiesChanged(replacement, fixture.capabilities)
        fixture.callback.onLinkPropertiesChanged(replacement, fixture.links)
        fixture.callback.onBlockedStatusChanged(replacement, false)
        val after = checkNotNull(provider.capture())
        assertEquals(before.scopeKey(), after.scopeKey())
        assertNull(before.directDnsUnderlayGeneration)
        assertNull(after.directDnsUnderlayGeneration)
        assertEquals("link_refresh", classifyNetworkHandover(before, after))
    }

    @Test
    fun `captive unvalidated physical readiness does not grant DNS authority`() {
        val fixture = physicalAndVpnNetworks()
        NetworkCapabilities::class.java
            .getDeclaredMethod("removeCapability", Int::class.javaPrimitiveType)
            .invoke(fixture.capabilities, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        NetworkCapabilities::class.java
            .getDeclaredMethod("addCapability", Int::class.javaPrimitiveType)
            .invoke(fixture.capabilities, NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        fixture.callback.onCapabilitiesChanged(fixture.physical, fixture.capabilities)
        val captured = checkNotNull(source(fixture).capture())
        assertEquals(false, captured.networkValidated)
        assertEquals(true, captured.captivePortalDetected)
        assertNull(captured.directDnsUnderlayGeneration)
    }

    private fun NetworkCapabilities.addTestTransport(transport: Int): NetworkCapabilities =
        apply {
            NetworkCapabilities::class.java
                .getDeclaredMethod("addTransportType", Int::class.javaPrimitiveType)
                .invoke(this, transport)
        }

    @Test
    fun `maps captured snapshot into a fingerprint`() {
        val provider =
            AndroidNetworkFingerprintProvider(
                snapshotSource =
                    FixedSnapshotSource(
                        CapturedNetworkSnapshot(
                            directDnsUnderlayGeneration = 17L,
                            transports = setOf(CapturedTransport.Wifi),
                            wifi = CapturedWifiIdentity(ssid = "\"Cafe Wifi\""),
                        ),
                    ),
                mapper = mapper,
            )

        val fingerprint = provider.capture()

        assertEquals("wifi", fingerprint?.transport)
        assertEquals("cafe wifi", fingerprint?.wifi?.ssid)
        assertEquals(17L, fingerprint?.directDnsUnderlayGeneration)
        assertEquals(fingerprint?.copy(directDnsUnderlayGeneration = null)?.scopeKey(), fingerprint?.scopeKey())
        val encoded = Json.encodeToString(NetworkFingerprint.serializer(), checkNotNull(fingerprint))
        assertEquals(false, encoded.contains("directDnsUnderlayGeneration"))
    }

    @Test
    fun `returns null when snapshot capture throws`() {
        val provider =
            AndroidNetworkFingerprintProvider(
                snapshotSource = ThrowingSnapshotSource(SecurityException("missing ACCESS_WIFI_STATE")),
                mapper = mapper,
            )

        assertNull(provider.capture())
    }

    private class FixedSnapshotSource(
        private val snapshot: CapturedNetworkSnapshot?,
    ) : AndroidNetworkSnapshotSource {
        override fun capture(): CapturedNetworkSnapshot? = snapshot
    }

    private class ThrowingSnapshotSource(
        private val error: RuntimeException,
    ) : AndroidNetworkSnapshotSource {
        override fun capture(): CapturedNetworkSnapshot? = throw error
    }
}

internal fun physicalNetworkTestCapabilities(transport: Int = NetworkCapabilities.TRANSPORT_WIFI): NetworkCapabilities =
    NetworkCapabilities().apply {
        NetworkCapabilities::class.java
            .getDeclaredMethod("addTransportType", Int::class.javaPrimitiveType)
            .invoke(this, transport)
        listOf(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
            NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED,
            NetworkCapabilities.NET_CAPABILITY_VALIDATED,
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
        ).forEach { capability ->
            NetworkCapabilities::class.java
                .getDeclaredMethod("addCapability", Int::class.javaPrimitiveType)
                .invoke(this, capability)
        }
    }

internal fun physicalNetworkTestRoute(gateway: String): RouteInfo =
    RouteInfo::class.java
        .getDeclaredConstructor(IpPrefix::class.java, InetAddress::class.java, String::class.java)
        .newInstance(IpPrefix(InetAddress.getByName("0.0.0.0"), 0), InetAddress.getByName(gateway), "test0")

internal fun physicalNetworkTestLinks(): LinkProperties =
    LinkProperties().apply {
        interfaceName = "test0"
        mtu = 1_500
        LinkProperties::class.java
            .getDeclaredMethod("addLinkAddress", LinkAddress::class.java)
            .invoke(
                this,
                LinkAddress::class.java
                    .getDeclaredConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)
                    .newInstance(InetAddress.getByName("192.0.2.2"), 24),
            )
        addRoute(physicalNetworkTestRoute("192.0.2.1"))
        setDnsServers(listOf(InetAddress.getByName("192.0.2.53")))
    }
