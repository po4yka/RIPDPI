package com.poyka.ripdpi.services

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
class VpnUnderlyingNetworkLeaseTest {
    @Test
    fun `underlay eligibility is fail closed for every missing capability`() {
        assertTrue(
            isDirectDnsUnderlayEligible(hasInternet = true, validated = true, notVpn = true, captivePortal = false),
        )
        assertFalse(
            isDirectDnsUnderlayEligible(hasInternet = false, validated = true, notVpn = true, captivePortal = false),
        )
        assertFalse(
            isDirectDnsUnderlayEligible(hasInternet = true, validated = false, notVpn = true, captivePortal = false),
        )
        assertFalse(
            isDirectDnsUnderlayEligible(hasInternet = true, validated = true, notVpn = false, captivePortal = false),
        )
        assertFalse(
            isDirectDnsUnderlayEligible(hasInternet = true, validated = true, notVpn = true, captivePortal = true),
        )
    }

    @Test
    fun `lease requires exact DNS set and exact policy network identity`() {
        val resolverA = InetAddress.getByName("1.1.1.1")
        val resolverB = InetAddress.getByName("8.8.8.8")
        val state = DirectDnsUnderlayLeaseState<String>()

        state.capture("wifi", snapshotGeneration = 10L, eligible = true, dnsServers = setOf(resolverA, resolverB))
        val partialToken = state.preparePolicy(setOf(resolverA), complete = true, networkGeneration = 10L)
        assertNull(state.preparedLease(partialToken))

        val wifiToken = state.preparePolicy(setOf(resolverA, resolverB), complete = true, networkGeneration = 10L)
        val wifiLease = checkNotNull(state.preparedLease(wifiToken))
        assertTrue(state.commitPrepared(wifiToken))
        assertTrue(state.isCommittedCurrent(wifiLease.leaseGeneration))

        assertFalse(
            state.capture("wifi", snapshotGeneration = 10L, eligible = true, dnsServers = setOf(resolverA, resolverB)),
        )
        assertTrue(state.isCommittedCurrent(wifiLease.leaseGeneration))

        state.capture("cell", snapshotGeneration = 20L, eligible = true, dnsServers = setOf(resolverA, resolverB))
        assertNull("same DNS set must not authorize a different underlay", state.committedLease())
        val cellToken = state.preparePolicy(setOf(resolverA, resolverB), complete = true, networkGeneration = 20L)
        val cellLease = checkNotNull(state.preparedLease(cellToken))
        assertTrue(state.commitPrepared(cellToken))
        assertTrue(cellLease.leaseGeneration > wifiLease.leaseGeneration)
        assertFalse(state.isCommittedCurrent(wifiLease.leaseGeneration))

        assertTrue(state.invalidate("cell"))
        assertNull(state.committedLease())
        assertFalse(state.isCommittedCurrent(cellLease.leaseGeneration))
    }

    @Test
    fun `ineligible network and incomplete numeric policy never create lease`() {
        val resolver = InetAddress.getByName("1.1.1.1")
        val state = DirectDnsUnderlayLeaseState<String>()

        state.capture("vpn", snapshotGeneration = 1L, eligible = false, dnsServers = setOf(resolver))
        val vpnToken = state.preparePolicy(setOf(resolver), complete = true, networkGeneration = 1L)
        assertNull(state.preparedLease(vpnToken))

        state.capture("wifi", snapshotGeneration = 2L, eligible = true, dnsServers = setOf(resolver))
        val incompleteToken = state.preparePolicy(setOf(resolver), complete = false, networkGeneration = 2L)
        assertNull(state.preparedLease(incompleteToken))
    }

    @Test
    fun `cold start relay hostname resolution uses eligible authority without direct DNS policy`() {
        val resolver = InetAddress.getByName("1.1.1.1")
        val leaseState = DirectDnsUnderlayLeaseState<String>()
        leaseState.capture("wifi", snapshotGeneration = 7L, eligible = true, dnsServers = setOf(resolver))
        assertNull("relay bootstrap must not depend on a split-DNS policy lease", leaseState.committedLease())

        var current = ResolverUnderlaySnapshot("wifi", generation = 7L)
        val stable = resolveOnCurrentUnderlay(snapshot = { current }, resolve = { arrayOf("192.0.2.10") })
        assertEquals(listOf("192.0.2.10"), stable?.toList())

        fun staleLookup(network: String): Array<String> {
            assertEquals("wifi", network)
            current = ResolverUnderlaySnapshot("cell", generation = 8L)
            return arrayOf("192.0.2.11")
        }
        val stale = resolveOnCurrentUnderlay(snapshot = { current }, resolve = ::staleLookup)
        assertNull("network change during relay bootstrap must reject the result", stale)
    }

    @Test
    fun `callback completion publishes only after VPN establishment`() {
        assertFalse(shouldPublishDirectDnsLease(snapshotChanged = false, livePublicationEnabled = false))
        assertFalse(shouldPublishDirectDnsLease(snapshotChanged = true, livePublicationEnabled = false))
        assertFalse(shouldPublishDirectDnsLease(snapshotChanged = false, livePublicationEnabled = true))
        assertTrue(shouldPublishDirectDnsLease(snapshotChanged = true, livePublicationEnabled = true))
    }

    @Test
    fun `no direct policy keeps default underlay and JNI lease unavailable`() {
        val resolver = InetAddress.getByName("1.1.1.1")
        val state = DirectDnsUnderlayLeaseState<String>()
        state.capture("wifi", snapshotGeneration = 1L, eligible = true, dnsServers = setOf(resolver))
        val token = state.preparePolicy(emptySet(), complete = true, networkGeneration = 1L)

        assertNull(vpnUnderlay(state.preparedLease(token), state.preparedRequiresDirectUnderlay(token)))
        assertEquals(0L, leaseGenerationOrZero(state.preparedLease(token)))

        var published: List<String>? = listOf("unexpected")
        assertFalse(
            applyPreparedDirectDnsLease(
                current = state.preparedLease(token),
                directUnderlayRequired = state.preparedRequiresDirectUnderlay(token),
                publish = { networks ->
                    published = networks
                    true
                },
                clearIfSame = { snapshot -> if (state.preparedLease(token) === snapshot) state.abortPrepared(token) },
            ),
        )
        assertNull("null retains Android's system-default encrypted-only underlay", published)
    }

    @Test
    fun `pre establish callback preserves exact builder lease until live publication`() {
        val resolver = InetAddress.getByName("1.1.1.1")
        val state = DirectDnsUnderlayLeaseState<String>()
        val token = state.preparePolicy(setOf(resolver), complete = true, networkGeneration = 7L)
        state.capture("wifi", snapshotGeneration = 7L, eligible = true, dnsServers = setOf(resolver))
        val prepared = checkNotNull(state.preparedLease(token))
        assertNull("prepared replacement must not become JNI-visible before native start", state.committedLease())

        assertFalse(shouldPublishDirectDnsLease(snapshotChanged = true, livePublicationEnabled = false))
        assertEquals(listOf("wifi"), vpnUnderlay(prepared, state.preparedRequiresDirectUnderlay(token)))
        assertEquals(prepared.leaseGeneration, leaseGenerationOrZero(prepared))

        val publications = mutableListOf<List<String>?>()
        assertTrue(
            applyPreparedDirectDnsLease(
                current = prepared,
                publish = { networks ->
                    publications += networks
                    true
                },
                clearIfSame = { snapshot -> if (state.preparedLease(token) === snapshot) state.abortPrepared(token) },
            ),
        )
        assertEquals(listOf(listOf("wifi")), publications)
    }

    @Test
    fun `preparing replacement B keeps committed A visible until exact commit or abort`() {
        val resolver = InetAddress.getByName("1.1.1.1")
        val state = DirectDnsUnderlayLeaseState<String>()
        state.capture("wifi", snapshotGeneration = 7L, eligible = true, dnsServers = setOf(resolver))
        val tokenA = state.preparePolicy(setOf(resolver), complete = true, networkGeneration = 7L)
        val preparedA = checkNotNull(state.preparedLease(tokenA))
        assertTrue(state.commitPrepared(tokenA))
        assertEquals(preparedA, state.committedLease())

        val tokenB = state.preparePolicy(setOf(resolver), complete = true, networkGeneration = 7L)
        val preparedB = checkNotNull(state.preparedLease(tokenB))

        assertTrue(preparedB.leaseGeneration > preparedA.leaseGeneration)
        assertEquals("old runtime must keep committed A while B is staged", preparedA, state.committedLease())
        assertTrue(state.isCommittedCurrent(preparedA.leaseGeneration))
        assertFalse(state.isCommittedCurrent(preparedB.leaseGeneration))
        assertTrue(state.abortPrepared(tokenB))
        assertEquals("failed establish must preserve committed A", preparedA, state.committedLease())
        assertNull(state.preparedLease(tokenB))
    }

    @Test
    fun `failed rebuild keeps active tunnel publication enabled for later handover`() {
        val resolver = InetAddress.getByName("1.1.1.1")
        val state = DirectDnsUnderlayLeaseState<String>()
        var activeTunnelPublicationEnabled = true

        state.capture("wifi", snapshotGeneration = 7L, eligible = true, dnsServers = setOf(resolver))
        val liveToken = state.preparePolicy(setOf(resolver), complete = true, networkGeneration = 7L)
        checkNotNull(state.preparedLease(liveToken))
        assertTrue(state.commitPrepared(liveToken))

        val failedToken = state.preparePolicy(setOf(resolver), complete = true, networkGeneration = 8L)
        assertTrue("policy preparation must not disable the established tunnel", activeTunnelPublicationEnabled)
        assertNull(state.preparedLease(failedToken))
        assertEquals(
            emptyList<String>(),
            vpnUnderlay(state.preparedLease(failedToken), state.preparedRequiresDirectUnderlay(failedToken)),
        )

        val failedRebuildPublications = mutableListOf<List<String>?>()
        assertFalse(
            applyPreparedDirectDnsLease(
                current = state.preparedLease(failedToken),
                directUnderlayRequired = state.preparedRequiresDirectUnderlay(failedToken),
                publish = { networks ->
                    failedRebuildPublications += networks
                    true
                },
                clearIfSame = { snapshot ->
                    if (state.preparedLease(failedToken) === snapshot) state.abortPrepared(failedToken)
                },
            ),
        )
        assertEquals(listOf(emptyList<String>()), failedRebuildPublications)

        state.capture("cell", snapshotGeneration = 8L, eligible = true, dnsServers = setOf(resolver))
        assertTrue(shouldPublishDirectDnsLease(snapshotChanged = true, activeTunnelPublicationEnabled))
        activeTunnelPublicationEnabled = false
        assertFalse(shouldPublishDirectDnsLease(snapshotChanged = true, activeTunnelPublicationEnabled))
    }
}
