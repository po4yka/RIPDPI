package com.poyka.ripdpi.services

import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.poyka.ripdpi.data.NetworkPathAssociationServiceBinder
import com.poyka.ripdpi.data.NetworkPathAssociationUnknown
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import java.net.Inet6Address
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VpnUnderlyingNetworkBinderTest {
    @Test
    @Config(sdk = [27])
    fun `pre Q underlay projection does not access newer link properties`() {
        val authority = DirectDnsUnderlayAuthority()
        val network = testNetwork(100)
        val epoch = authority.beginCallbackEpoch()

        authority.onAvailable(epoch, network)
        authority.onCapabilitiesChanged(epoch, network, eligibleCapabilities())
        authority.onLinkPropertiesChanged(epoch, network, linkProperties("1.1.1.1"))

        val observation = authority.capture()
        assertFalse(requireNotNull(observation.nat64Present))
        assertEquals("unknown", observation.mtuBand)
    }

    @Test
    fun `diagnostic underlay observation is authoritative bounded and identifier free`() {
        val authority = DirectDnsUnderlayAuthority()
        val network = testNetwork(100)
        val epoch = authority.beginCallbackEpoch()
        val capabilities = eligibleCapabilities()
        val links =
            linkProperties("203.0.113.53").also {
                it.interfaceName = "wlan-secret"
                it.mtu = 1_420
                it.nat64Prefix = IpPrefix(InetAddress.getByName("64:ff9b::"), 96)
            }

        authority.onAvailable(epoch, network)
        authority.onCapabilitiesChanged(epoch, network, capabilities)
        authority.onLinkPropertiesChanged(epoch, network, links)

        val observation = authority.capture()
        val encoded = Json.encodeToString(observation)
        assertEquals(NetworkPathAssociationServiceBinder, observation.association)
        assertEquals("other", observation.transport)
        assertEquals(emptyList<String>(), observation.addressFamilies)
        assertEquals(listOf("ipv4"), observation.dnsServerFamilies)
        assertEquals("reduced", observation.mtuBand)
        assertFalse(requireNotNull(observation.congested))
        assertFalse(requireNotNull(observation.restricted))
        assertTrue(requireNotNull(observation.nat64Present))
        listOf("wlan-secret", "203.0.113.53", "64:ff9b").forEach { forbidden ->
            assertFalse("must not contain $forbidden", encoded.contains(forbidden, ignoreCase = true))
        }
    }

    @Test
    fun `diagnostic underlay generation is stable for duplicates and lost fails closed`() {
        val authority = DirectDnsUnderlayAuthority()
        val network = testNetwork(100)
        val epoch = authority.beginCallbackEpoch()
        val capabilities = eligibleCapabilities()
        val links = linkProperties("1.1.1.1").also { it.mtu = 1_500 }

        authority.onAvailable(epoch, network)
        authority.onCapabilitiesChanged(epoch, network, capabilities)
        authority.onLinkPropertiesChanged(epoch, network, links)
        val first = authority.capture()

        authority.onCapabilitiesChanged(epoch, network, capabilities)
        authority.onLinkPropertiesChanged(epoch, network, links)
        assertEquals(first.generation, authority.capture().generation)

        authority.onLinkPropertiesChanged(epoch, network, linkProperties("1.1.1.1").also { it.mtu = 1_420 })
        assertTrue(requireNotNull(authority.capture().generation) > requireNotNull(first.generation))

        authority.onLost(epoch, network)
        assertEquals(NetworkPathAssociationUnknown, authority.capture().association)
        assertNull(authority.capture().generation)
    }

    @Test
    fun `diagnostic underlay stays unknown until complete then preserves failure evidence`() {
        val authority = DirectDnsUnderlayAuthority()
        val network = testNetwork(100)
        val epoch = authority.beginCallbackEpoch()

        authority.onAvailable(epoch, network)
        assertEquals(NetworkPathAssociationUnknown, authority.capture().association)

        authority.onCapabilitiesChanged(epoch, network, eligibleCapabilities())
        assertEquals(NetworkPathAssociationUnknown, authority.capture().association)

        authority.onLinkPropertiesChanged(epoch, network, LinkProperties())
        val dnsMissing = authority.capture()
        assertEquals(NetworkPathAssociationServiceBinder, dnsMissing.association)
        assertEquals(0, dnsMissing.dnsServerCount)
        assertEquals(emptyList<String>(), dnsMissing.dnsServerFamilies)

        authority.onLinkPropertiesChanged(epoch, network, linkProperties("1.1.1.1"))
        assertEquals(NetworkPathAssociationServiceBinder, authority.capture().association)

        val ineligibleNetwork = testNetwork(200)
        val failingCapabilities =
            NetworkCapabilities().also { capabilities ->
                listOf(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET,
                    NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
                    NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL,
                ).forEach { capability ->
                    NetworkCapabilities::class
                        .java
                        .getDeclaredMethod("addCapability", Int::class.javaPrimitiveType)
                        .invoke(capabilities, capability)
                }
            }
        authority.onAvailable(epoch, ineligibleNetwork)
        authority.onCapabilitiesChanged(epoch, ineligibleNetwork, failingCapabilities)
        authority.onLinkPropertiesChanged(epoch, ineligibleNetwork, linkProperties("1.1.1.1"))
        val ineligible = authority.capture()
        assertEquals(NetworkPathAssociationServiceBinder, ineligible.association)
        assertFalse(requireNotNull(ineligible.validated))
        assertTrue(requireNotNull(ineligible.captivePortal))
    }

    @Test
    fun `complete vpn callback is never captured as authoritative underlay`() {
        val authority = DirectDnsUnderlayAuthority()
        val network = testNetwork(100)
        val epoch = authority.beginCallbackEpoch()
        val vpnCapabilities =
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

        authority.onAvailable(epoch, network)
        authority.onCapabilitiesChanged(epoch, network, vpnCapabilities)
        authority.onLinkPropertiesChanged(epoch, network, linkProperties("1.1.1.1"))

        val observation = authority.capture()
        assertEquals(NetworkPathAssociationUnknown, observation.association)
        assertNull(observation.generation)
    }

    @Test
    fun `authority changes are monotonic and stale eligibility waiters terminate`() =
        runTest {
            val authority = DirectDnsUnderlayAuthority()
            val network = testNetwork(100)
            val epoch = authority.beginCallbackEpoch()
            val initialChange = authority.changes.value
            val staleWait =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { authority.awaitEligible(epoch) }
                }

            authority.onAvailable(epoch, network)
            assertTrue(authority.changes.value > initialChange)
            authority.endCallbackEpoch(epoch)

            assertTrue(staleWait.await().isFailure)
        }

    @Test
    fun `authority readiness waits for a validated non vpn network with dns`() =
        runTest {
            val authority = DirectDnsUnderlayAuthority()
            val network = testNetwork(100)
            val epoch = authority.beginCallbackEpoch()
            val ready = async(start = CoroutineStart.UNDISPATCHED) { authority.awaitEligible(epoch) }

            authority.onAvailable(epoch, network)
            authority.onLinkPropertiesChanged(epoch, network, linkProperties("1.1.1.1"))
            assertFalse(ready.isCompleted)

            authority.onCapabilitiesChanged(epoch, network, eligibleCapabilities())
            ready.await()
        }

    @Test
    fun `authority token is exact monotonic and ignores stale callback interleavings`() {
        val authority = DirectDnsUnderlayAuthority()
        val networkA = testNetwork(100)
        val networkB = testNetwork(200)
        val capabilities = eligibleCapabilities()
        val links = linkProperties("1.1.1.1")
        val epoch = authority.beginCallbackEpoch()

        authority.onAvailable(epoch, networkA)
        authority.onLinkPropertiesChanged(epoch, networkA, links)
        assertNull("capabilities are required", authority.snapshot(epoch))
        authority.onCapabilitiesChanged(epoch, networkA, capabilities)
        val tokenA = checkNotNull(authority.generationFor(networkA, capabilities, links))
        assertEquals(tokenA, authority.generationFor(networkA, capabilities, links))

        authority.onCapabilitiesChanged(epoch, networkA, capabilities)
        authority.onLinkPropertiesChanged(epoch, networkA, links)
        assertEquals("duplicate callbacks retain generation", tokenA, authority.snapshot(epoch)?.generation)

        authority.onAvailable(epoch, networkB)
        authority.onCapabilitiesChanged(epoch, networkA, capabilities)
        authority.onLinkPropertiesChanged(epoch, networkA, links)
        assertNull("late A callbacks cannot revive A", authority.generationFor(networkA, capabilities, links))
        authority.onCapabilitiesChanged(epoch, networkB, capabilities)
        authority.onLinkPropertiesChanged(epoch, networkB, links)
        val tokenB = checkNotNull(authority.generationFor(networkB, capabilities, links))
        assertTrue(tokenB > tokenA)

        authority.onLost(epoch, networkB)
        authority.onAvailable(epoch, networkB)
        authority.onLinkPropertiesChanged(epoch, networkB, links)
        authority.onCapabilitiesChanged(epoch, networkB, capabilities)
        val tokenB2 = checkNotNull(authority.generationFor(networkB, capabilities, links))
        assertTrue("ABA gets a fresh token", tokenB2 > tokenB)

        authority.endCallbackEpoch(epoch)
        authority.onAvailable(epoch, networkA)
        assertNull("old callback epoch cannot resurrect state", authority.snapshot(epoch))
    }

    @Test
    fun `numeric parser rejects hostname and noncanonical IPv4 without resolving`() {
        assertNull(parseDirectDnsNumericAddress("resolver.example"))
        assertNull(parseDirectDnsNumericAddress("001.1.1.1"))
        assertEquals("1.1.1.1", parseDirectDnsNumericAddress("1.1.1.1")?.hostAddress)
    }

    @Test
    @Config(sdk = [27])
    fun `numeric parser accepts IPv6 on min sdk without hostname fallback`() {
        assertTrue(parseDirectDnsNumericAddress("2001:4860:4860::8888") is Inet6Address)
        assertNull(parseDirectDnsNumericAddress("2001:db8::\u0661"))
        assertNull(parseDirectDnsNumericAddress(" 2001:db8::1"))
        assertNull(parseDirectDnsNumericAddress("fe80::1%wlan0"))
    }

    private fun testNetwork(id: Int): Network = ShadowNetwork.newInstance(id)

    private fun eligibleCapabilities(): NetworkCapabilities =
        NetworkCapabilities().also { capabilities ->
            val supportedCapabilities =
                buildList {
                    add(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    add(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    add(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    add(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        add(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
                    }
                }
            supportedCapabilities.forEach { capability ->
                NetworkCapabilities::class
                    .java
                    .getDeclaredMethod("addCapability", Int::class.javaPrimitiveType)
                    .invoke(capabilities, capability)
            }
        }

    private fun linkProperties(dns: String): LinkProperties =
        LinkProperties().also { links -> links.setDnsServers(listOf(InetAddress.getByName(dns))) }
}
