package com.poyka.ripdpi.services.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkCallbackEventsTest {
    @Test
    fun `onAvailable dispatches Available event`() {
        val dispatched = mutableListOf<NetworkEvent>()
        val bridge = FakeNetworkEventBridge { dispatched += it }

        bridge.onAvailable()

        assertEquals(1, dispatched.size)
        assertTrue(dispatched.single() is NetworkEvent.Available)
    }

    @Test
    fun `onCapabilitiesChanged dispatches CapabilitiesChanged event`() {
        val dispatched = mutableListOf<NetworkEvent>()
        val bridge = FakeNetworkEventBridge { dispatched += it }
        val caps =
            FakeNetworkCapabilities(
                validated = true,
                captivePortal = false,
                metered = false,
                suspended = false,
                transports = setOf(FakeTransport.Wifi),
            )

        bridge.onCapabilitiesChanged(caps)

        assertEquals(1, dispatched.size)
        val event = dispatched.single()
        assertTrue(event is NetworkEvent.CapabilitiesChanged)
        val snapshot = (event as NetworkEvent.CapabilitiesChanged).snapshot
        assertTrue(snapshot.validated)
        assertEquals(setOf(FakeTransport.Wifi), snapshot.transports)
    }

    @Test
    fun `onLinkPropertiesChanged dispatches LinkPropertiesChanged event`() {
        val dispatched = mutableListOf<NetworkEvent>()
        val bridge = FakeNetworkEventBridge { dispatched += it }
        val link = FakeLinkProperties(dnsServers = listOf("1.1.1.1"), hasDefaultRoute = true)

        bridge.onLinkPropertiesChanged(link)

        assertEquals(1, dispatched.size)
        val event = dispatched.single()
        assertTrue(event is NetworkEvent.LinkPropertiesChanged)
        val snapshot = (event as NetworkEvent.LinkPropertiesChanged).snapshot
        assertEquals(listOf("1.1.1.1"), snapshot.dnsServers)
        assertTrue(snapshot.hasDefaultRoute)
    }

    @Test
    fun `onLost dispatches Lost event`() {
        val dispatched = mutableListOf<NetworkEvent>()
        val bridge = FakeNetworkEventBridge { dispatched += it }

        bridge.onLost()

        assertEquals(1, dispatched.size)
        assertTrue(dispatched.single() is NetworkEvent.Lost)
    }

    @Test
    fun `all four callbacks each produce exactly one event without polling`() {
        val dispatched = mutableListOf<NetworkEvent>()
        val bridge = FakeNetworkEventBridge { dispatched += it }
        val caps = FakeNetworkCapabilities()
        val link = FakeLinkProperties()

        bridge.onAvailable()
        bridge.onCapabilitiesChanged(caps)
        bridge.onLinkPropertiesChanged(link)
        bridge.onLost()

        assertEquals(4, dispatched.size)
        assertTrue(dispatched[0] is NetworkEvent.Available)
        assertTrue(dispatched[1] is NetworkEvent.CapabilitiesChanged)
        assertTrue(dispatched[2] is NetworkEvent.LinkPropertiesChanged)
        assertTrue(dispatched[3] is NetworkEvent.Lost)
    }
}

// ---------------------------------------------------------------------------
// Test doubles — no Android runtime required
// ---------------------------------------------------------------------------

enum class FakeTransport { Wifi, Cellular, Ethernet }

data class FakeNetworkCapabilities(
    val validated: Boolean = false,
    val captivePortal: Boolean = false,
    val metered: Boolean = false,
    val suspended: Boolean = false,
    val transports: Set<FakeTransport> = emptySet(),
)

data class FakeLinkProperties(
    val dnsServers: List<String> = emptyList(),
    val hasDefaultRoute: Boolean = false,
)

/** Adapter that bridges fake callbacks into [NetworkEvent]s — the unit under test. */
class FakeNetworkEventBridge(
    private val onEvent: (NetworkEvent) -> Unit,
) {
    fun onAvailable() {
        onEvent(NetworkEvent.Available)
    }

    fun onCapabilitiesChanged(caps: FakeNetworkCapabilities) {
        onEvent(
            NetworkEvent.CapabilitiesChanged(
                snapshot =
                    CapabilitiesSnapshot(
                        validated = caps.validated,
                        captivePortal = caps.captivePortal,
                        metered = caps.metered,
                        suspended = caps.suspended,
                        transports = caps.transports,
                    ),
            ),
        )
    }

    fun onLinkPropertiesChanged(link: FakeLinkProperties) {
        onEvent(
            NetworkEvent.LinkPropertiesChanged(
                snapshot =
                    LinkPropertiesSnapshot(
                        dnsServers = link.dnsServers,
                        hasDefaultRoute = link.hasDefaultRoute,
                    ),
            ),
        )
    }

    fun onLost() {
        onEvent(NetworkEvent.Lost)
    }
}
