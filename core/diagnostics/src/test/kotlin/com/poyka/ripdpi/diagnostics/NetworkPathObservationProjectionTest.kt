package com.poyka.ripdpi.diagnostics

import android.net.IpPrefix
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import com.poyka.ripdpi.data.NetworkPathAssociationActiveDefault
import com.poyka.ripdpi.data.NetworkPathAssociationServiceBinder
import com.poyka.ripdpi.data.NetworkPathAssociationUnknown
import com.poyka.ripdpi.data.NetworkPathObservation
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
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetworkPathObservationProjectionTest {
    @Test
    fun `pre Q projection does not access newer link properties`() {
        val observation =
            projectActiveVpnObservation(
                capabilities(NetworkCapabilities.TRANSPORT_VPN),
                LinkProperties(),
                generation = 1L,
                platformSdk = Build.VERSION_CODES.O_MR1,
            )

        assertFalse(requireNotNull(observation.nat64Present))
        assertEquals("unknown", observation.mtuBand)
    }

    @Test
    @Config(sdk = [29])
    fun `vpn projection captures mtu before nat64 becomes available`() {
        val links = LinkProperties().also { it.mtu = 1_280 }

        val observation =
            projectActiveVpnObservation(
                capabilities(NetworkCapabilities.TRANSPORT_VPN),
                links,
                generation = 1L,
            )

        assertFalse(requireNotNull(observation.nat64Present))
        assertEquals("reduced", observation.mtuBand)
    }

    @Test
    fun `only actual active vpn capabilities produce active default observation`() {
        val nonVpn = projectActiveVpnObservation(capabilities(transport = null), LinkProperties(), generation = 1L)
        assertEquals(NetworkPathAssociationUnknown, nonVpn.association)
        assertNull(nonVpn.generation)

        val vpn = projectActiveVpnObservation(capabilities(NetworkCapabilities.TRANSPORT_VPN), LinkProperties(), 2L)
        assertEquals(NetworkPathAssociationActiveDefault, vpn.association)
        assertEquals(2L, vpn.generation)
        assertEquals("vpn", vpn.transport)
    }

    @Test
    fun `vpn projection exports only categories families counts and bands`() {
        val links =
            LinkProperties().also {
                it.interfaceName = "tun-sensitive"
                it.setDnsServers(listOf(InetAddress.getByName("198.51.100.53"), InetAddress.getByName("2001:db8::53")))
                it.mtu = 1_280
                it.nat64Prefix = IpPrefix(InetAddress.getByName("64:ff9b::"), 96)
            }
        val observation =
            projectActiveVpnObservation(
                capabilities(NetworkCapabilities.TRANSPORT_VPN),
                links,
                generation = 9L,
            )
        val encoded = Json.encodeToString(observation)

        assertEquals(listOf("ipv4", "ipv6"), observation.dnsServerFamilies)
        assertEquals(2, observation.dnsServerCount)
        assertEquals("reduced", observation.mtuBand)
        assertFalse(requireNotNull(observation.congested))
        assertFalse(requireNotNull(observation.restricted))
        assertTrue(requireNotNull(observation.nat64Present))
        listOf("tun-sensitive", "198.51.100.53", "2001:db8", "64:ff9b").forEach { forbidden ->
            assertFalse("must not contain $forbidden", encoded.contains(forbidden, ignoreCase = true))
        }
    }

    @Test
    fun `underlay source and vpn generations fail closed and remain path local`() {
        val untrusted = NetworkPathObservation(association = NetworkPathAssociationActiveDefault, generation = 44L)
        assertEquals(NetworkPathObservation(), sanitizeAuthoritativeUnderlayObservation(untrusted))

        val trusted = NetworkPathObservation(association = NetworkPathAssociationServiceBinder, generation = 45L)
        assertEquals(trusted, sanitizeAuthoritativeUnderlayObservation(trusted))

        val tracker = ActiveVpnPathGenerationTracker()
        val shape = NetworkPathObservation(association = NetworkPathAssociationActiveDefault, transport = "vpn")
        val first = tracker.generationFor("network-a", shape)
        assertEquals(first, tracker.generationFor("network-a", shape))
        assertTrue(tracker.generationFor("network-b", shape) > first)
    }

    private fun capabilities(transport: Int?): NetworkCapabilities =
        NetworkCapabilities().also { capabilities ->
            val supportedCapabilities =
                buildList {
                    add(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    add(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    add(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        add(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
                        add(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                        add(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
                    }
                }
            supportedCapabilities.forEach { capability ->
                invokeIntMethod(capabilities, "addCapability", capability)
            }
            transport?.let { invokeIntMethod(capabilities, "addTransportType", it) }
        }

    private fun invokeIntMethod(
        target: Any,
        name: String,
        value: Int,
    ) {
        target.javaClass.getDeclaredMethod(name, Int::class.javaPrimitiveType).invoke(target, value)
    }
}
