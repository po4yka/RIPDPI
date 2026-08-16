package com.poyka.ripdpi.services

import android.os.Build
import com.poyka.ripdpi.data.VpnRouteCallbackState
import com.poyka.ripdpi.data.VpnRouteConsistency
import com.poyka.ripdpi.data.VpnRouteFamilyIpv4
import com.poyka.ripdpi.data.VpnRouteLifecycleState
import com.poyka.ripdpi.data.VpnRouteObservationConvergenceMillis
import com.poyka.ripdpi.data.VpnRouteOwnerVerification
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnRouteLifecycleReceiptStoreTest {
    @Test
    fun `forwarding outcome is correlated to current lifecycle generation`() {
        val store = VpnRouteLifecycleReceiptStore()
        val generation = store.beginTestGeneration()
        store.markEstablished(generation)

        store.recordForwardingOutcome(generation, "cross_layer_return_observed", terminal = false, revision = 1L)

        assertEquals("cross_layer_return_observed", store.capture().forwardingOutcome)
        assertEquals(generation, store.capture().forwardingLifecycleGeneration)
        assertEquals(false, store.capture().forwardingTerminal)
        store.recordForwardingOutcome(generation - 1L, "tun_ingress_no_upstream", terminal = true, revision = 2L)
        assertEquals("cross_layer_return_observed", store.capture().forwardingOutcome)
    }

    @Test
    fun `older forwarding poll cannot overwrite newer terminal evidence`() {
        val store = VpnRouteLifecycleReceiptStore()
        val generation = store.beginTestGeneration()
        store.markEstablished(generation)

        store.recordForwardingOutcome(generation, "tun_ingress_no_upstream", terminal = true, revision = 2L)
        store.recordForwardingOutcome(generation, "no_flow", terminal = false, revision = 1L)

        assertEquals("tun_ingress_no_upstream", store.capture().forwardingOutcome)
        assertEquals(true, store.capture().forwardingTerminal)
    }

    @Test
    fun `newer healthy forwarding poll clears a recovered terminal failure`() {
        val store = VpnRouteLifecycleReceiptStore()
        val generation = store.beginTestGeneration()
        store.markEstablished(generation)

        store.recordForwardingOutcome(generation, "tun_ingress_no_upstream", terminal = true, revision = 1L)
        store.recordForwardingOutcome(generation, "cross_layer_return_observed", terminal = false, revision = 2L)

        assertEquals("cross_layer_return_observed", store.capture().forwardingOutcome)
        assertEquals(false, store.capture().forwardingTerminal)
    }

    @Test
    fun `awaiting callback becomes timed out after bounded convergence window`() {
        var nowNanos = 1L
        val store = VpnRouteLifecycleReceiptStore { nowNanos }
        val generation = store.beginTestGeneration()
        store.markEstablished(generation)

        nowNanos += (VpnRouteObservationConvergenceMillis - 1_000L) * 1_000_000L
        store.recordForwardingOutcome(generation, "no_flow", terminal = false, revision = 1L)
        assertEquals(1_000L, store.convergenceRefreshDelayMillis())
        nowNanos += 1_000L * 1_000_000L

        assertEquals(VpnRouteCallbackState.TimedOut, store.capture().callbackState)
    }

    @Test
    fun `published lifecycle evidence reports bounded freshness`() {
        val store = VpnRouteLifecycleReceiptStore()
        store.beginTestGeneration()

        assertEquals("fresh", store.capture().evidenceAgeBand)
    }

    @Test
    fun `network available before replacement intent cannot satisfy replacement evidence`() {
        val store = VpnRouteLifecycleReceiptStore()
        val previousGeneration = store.beginTestGeneration()
        store.markEstablished(previousGeneration)
        store.observeAvailable("vpn-old")

        val replacementGeneration = store.beginTestGeneration()
        store.markEstablished(replacementGeneration)
        store.observeVerifiedCapabilities("vpn-old")
        store.observeDefaultRoutes("vpn-old", setOf(VpnRouteFamilyIpv4))

        assertEquals(replacementGeneration, store.capture().lifecycle?.generation)
        assertEquals(VpnRouteCallbackState.Awaiting, store.capture().callbackState)
    }

    @Test
    fun `late loss of previous vpn keeps a partial replacement awaiting`() {
        val store = VpnRouteLifecycleReceiptStore()
        val previousGeneration = store.beginTestGeneration()
        store.markEstablished(previousGeneration)
        store.observeVerifiedNetworkShape("vpn-old")

        val replacementGeneration = store.beginTestGeneration()
        store.markEstablished(replacementGeneration)
        store.observeVerifiedCapabilities("vpn-new")
        store.observeLost("vpn-old")

        val evidence = store.capture()
        assertEquals(replacementGeneration, evidence.lifecycle?.generation)
        assertEquals(VpnRouteCallbackState.Awaiting, evidence.callbackState)
        assertEquals(null, evidence.vpnPresent)
    }

    @Test
    fun `new intent replaces a closed generation as current lifecycle evidence`() {
        val store = VpnRouteLifecycleReceiptStore()
        val closedGeneration = store.beginTestGeneration()
        store.markEstablished(closedGeneration)
        store.markEnded(closedGeneration, VpnRouteLifecycleState.Closed)

        val intendedGeneration = store.beginTestGeneration()

        assertEquals(intendedGeneration, store.lifecycleReceipt?.generation)
        assertEquals(VpnRouteLifecycleState.Intended, store.lifecycleReceipt?.state)
    }

    @Test
    fun `callback publishes complete evidence only after capabilities and routes correlate`() {
        val store = VpnRouteLifecycleReceiptStore()
        val generation = store.beginTestGeneration()
        store.markEstablished(generation)

        store.observeCapabilities(
            networkKey = "vpn-a",
            hasInternet = true,
            validated = true,
            captivePortal = false,
            ownerVerification = VpnRouteOwnerVerification.Verified,
        )
        assertEquals(VpnRouteCallbackState.Awaiting, store.capture().callbackState)

        store.observeNetworkShape(
            networkKey = "vpn-a",
            hasInternet = true,
            validated = true,
            captivePortal = false,
            ownerVerification = VpnRouteOwnerVerification.Verified,
            families = setOf(VpnRouteFamilyIpv4),
        )

        val evidence = store.capture()
        assertEquals(VpnRouteCallbackState.Complete, evidence.callbackState)
        assertEquals(VpnRouteConsistency.Consistent, evidence.routeConsistency)
        assertEquals(true, evidence.vpnPresent)
        assertEquals(true, evidence.validated)
        assertEquals(listOf(VpnRouteFamilyIpv4), evidence.observedDefaultRouteFamilies)
        assertEquals(VpnRouteOwnerVerification.Verified, evidence.ownerVerification)
    }

    @Test
    fun `new partial callback shape cannot reuse the previous revision half`() {
        val store = VpnRouteLifecycleReceiptStore()
        val generation = store.beginTestGeneration()
        store.markEstablished(generation)
        store.observeNetworkShape(
            networkKey = "vpn-a",
            hasInternet = true,
            validated = true,
            captivePortal = false,
            ownerVerification = VpnRouteOwnerVerification.Verified,
            families = setOf(VpnRouteFamilyIpv4),
        )

        store.observeCapabilities(
            networkKey = "vpn-a",
            hasInternet = true,
            validated = false,
            captivePortal = false,
            ownerVerification = VpnRouteOwnerVerification.Verified,
        )

        assertEquals(VpnRouteCallbackState.Awaiting, store.capture().callbackState)
    }

    @Test
    fun `same-network handover publishes one coherent callback revision`() {
        val store = VpnRouteLifecycleReceiptStore()
        val generation = store.beginTestGeneration()
        store.markEstablished(generation)
        store.observeNetworkShape(
            networkKey = "vpn-a",
            hasInternet = true,
            validated = true,
            captivePortal = false,
            ownerVerification = VpnRouteOwnerVerification.Verified,
            families = setOf(VpnRouteFamilyIpv4),
        )
        val initialRevision = store.capture().callbackRevision

        store.observeNetworkShape(
            networkKey = "vpn-a",
            hasInternet = true,
            validated = false,
            captivePortal = false,
            ownerVerification = VpnRouteOwnerVerification.Verified,
            families = emptySet(),
        )

        val handover = store.capture()
        assertEquals(VpnRouteCallbackState.Complete, handover.callbackState)
        assertEquals(VpnRouteConsistency.Mismatch, handover.routeConsistency)
        assertEquals(false, handover.validated)
        assertEquals(requireNotNull(initialRevision) + 1L, handover.callbackRevision)
    }
}

private fun VpnRouteLifecycleReceiptStore.observeVerifiedCapabilities(networkKey: Any) {
    observeCapabilities(
        networkKey = networkKey,
        hasInternet = true,
        validated = true,
        captivePortal = false,
        ownerVerification = VpnRouteOwnerVerification.Verified,
    )
}

private fun VpnRouteLifecycleReceiptStore.observeVerifiedNetworkShape(networkKey: Any) {
    observeNetworkShape(
        networkKey = networkKey,
        hasInternet = true,
        validated = true,
        captivePortal = false,
        ownerVerification = VpnRouteOwnerVerification.Verified,
        families = setOf(VpnRouteFamilyIpv4),
    )
}

private fun VpnRouteLifecycleReceiptStore.beginTestGeneration(): Long =
    beginIntended(
        ipv6Enabled = false,
        dns = "1.1.1.1",
        appRoutingPlan = VpnAppRoutingPlan.Disallow(setOf("com.poyka.ripdpi")),
        networkParameters = VpnTunnelNetworkParameters(),
        apiLevel = Build.VERSION_CODES.Q,
    )
