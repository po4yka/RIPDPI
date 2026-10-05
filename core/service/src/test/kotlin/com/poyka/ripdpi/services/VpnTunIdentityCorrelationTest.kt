package com.poyka.ripdpi.services

import android.os.Build
import com.poyka.ripdpi.data.VpnRouteCallbackState
import com.poyka.ripdpi.data.VpnRouteFamilyIpv4
import com.poyka.ripdpi.data.VpnRouteLifecycleState
import com.poyka.ripdpi.data.VpnRouteOwnerVerification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal val testRouteTunIdentity = checkNotNull(PrivateTunIdentity.create("test0", 17))

class VpnTunIdentityCorrelationTest {
    @Test
    fun `live rebuild cannot inherit verified owner from a stale different agent`() {
        val store = VpnRouteLifecycleReceiptStore()
        val staleIdentity = checkNotNull(PrivateTunIdentity.create("reused0", 16))
        store.observeCapabilities("stale-agent", true, true, false, VpnRouteOwnerVerification.Verified)
        store.observeDefaultRoutes("stale-agent", setOf(VpnRouteFamilyIpv4), staleIdentity)
        val liveGeneration = store.beginGeneration()
        store.markEstablished(liveGeneration, testRouteTunIdentity)
        store.verifiedCapabilities()
        store.routes(testRouteTunIdentity)
        store.markBridgeReady(liveGeneration)
        assertTrue(store.capture().isEligibleForInPathLease())

        val reusedName = checkNotNull(PrivateTunIdentity.create("reused0", 18))
        val replacement = store.beginGeneration()
        store.markEstablished(replacement, reusedName)
        store.markBridgeReady(replacement)
        store.observeDefaultRoutes("stale-agent", setOf(VpnRouteFamilyIpv4), reusedName)
        assertFalse(store.capture().isEligibleForInPathLease())
        store.routes(reusedName)
        assertTrue(store.capture().isEligibleForInPathLease())
    }

    @Test
    fun `stopped or fail closed generation cannot authenticate queued LP through reused name`() {
        listOf(VpnRouteLifecycleState.Closed, VpnRouteLifecycleState.FailClosed).forEach { state ->
            val store = VpnRouteLifecycleReceiptStore()
            val previous = store.beginGeneration()
            store.markEstablished(previous, testRouteTunIdentity)
            store.verifiedCapabilities()
            store.routes(testRouteTunIdentity)
            store.markBridgeReady(previous)
            store.markEnded(previous, state)

            val reusedName = checkNotNull(PrivateTunIdentity.create("test0", 18))
            val restarted = store.beginGeneration()
            store.markEstablished(restarted, reusedName)
            store.markBridgeReady(restarted)
            // A queued old-agent LP names test0; the current lookup resolves
            // the newly allocated index. It must not reuse the old owner proof.
            store.routes(reusedName)
            assertFalse("state=$state", store.capture().isEligibleForInPathLease())
            store.verifiedCapabilities()
            store.routes(reusedName)
            assertFalse("old agent remains fenced, state=$state", store.capture().isEligibleForInPathLease())

            store.observeCapabilities("new-agent", true, true, false, VpnRouteOwnerVerification.Verified)
            store.observeDefaultRoutes("new-agent", setOf(VpnRouteFamilyIpv4), reusedName)
            assertTrue("fresh new agent admits, state=$state", store.capture().isEligibleForInPathLease())
        }
    }

    @Test
    fun `late loss of previous interface cannot claim replacement VPN is absent`() {
        val store = VpnRouteLifecycleReceiptStore()
        store.markEstablished(store.beginGeneration(), testRouteTunIdentity)
        store.verifiedCapabilities()
        store.routes(testRouteTunIdentity)
        val replacement = store.beginGeneration()
        store.markEstablished(replacement, PrivateTunIdentity.create("replacement0", 18))
        store.markBridgeReady(replacement)

        store.observeLost("vpn-agent")

        assertEquals(VpnRouteCallbackState.Awaiting, store.capture().callbackState)
        assertNull(store.capture().vpnPresent)
        assertFalse(store.capture().isEligibleForInPathLease())
    }

    @Test
    fun `late establishment cannot replace newer generation identity`() {
        val store = VpnRouteLifecycleReceiptStore()
        val older = store.beginGeneration()
        val newer = store.beginGeneration()
        store.markEstablished(newer, testRouteTunIdentity)
        store.markBridgeReady(newer)
        store.verifiedCapabilities()
        store.routes(testRouteTunIdentity)
        assertTrue(store.capture().isEligibleForInPathLease())

        store.markEstablished(older, PrivateTunIdentity.create("older0", 18))

        assertEquals(newer, store.capture().lifecycle?.generation)
        assertTrue(store.capture().isEligibleForInPathLease())
    }

    @Test
    fun `same agent replacement accepts retained verified owner and fresh matching LP only`() {
        val store = VpnRouteLifecycleReceiptStore()
        val previous = store.beginGeneration()
        store.markEstablished(previous, testRouteTunIdentity)
        store.verifiedCapabilities()
        store.routes(testRouteTunIdentity)
        store.markBridgeReady(previous)
        val previousRevision = checkNotNull(store.capture().callbackRevision)

        val replacementIdentity = checkNotNull(PrivateTunIdentity.create("replacement0", 18))
        val replacement = store.beginGeneration()
        store.markEstablished(replacement, replacementIdentity)
        store.markBridgeReady(replacement)
        assertFalse(store.capture().isEligibleForInPathLease())
        store.routes(replacementIdentity)

        val evidence = store.capture()
        assertEquals(replacement, evidence.lifecycle?.generation)
        assertEquals(VpnRouteCallbackState.Complete, evidence.callbackState)
        assertTrue(evidence.isEligibleForInPathLease())
        assertTrue(checkNotNull(evidence.callbackRevision) > previousRevision)
        assertFalse(evidence.toString().contains("replacement0"))
        store.markEnded(previous, VpnRouteLifecycleState.Closed)
        assertTrue(store.capture().isEligibleForInPathLease())
    }

    @Test
    fun `replacement rejects stale missing mismatched and same name different index routes`() {
        listOf(
            null,
            PrivateTunIdentity.create("other0", 17),
            PrivateTunIdentity.create("test0", 18),
        ).forEach { observedIdentity ->
            val store = VpnRouteLifecycleReceiptStore()
            val previous = store.beginGeneration()
            store.markEstablished(previous, testRouteTunIdentity)
            store.verifiedCapabilities()
            store.routes(testRouteTunIdentity)
            val replacement = store.beginGeneration()
            store.markEstablished(replacement, testRouteTunIdentity)
            store.markBridgeReady(replacement)
            assertEquals(VpnRouteCallbackState.Awaiting, store.capture().callbackState)
            store.routes(observedIdentity)
            assertEquals(VpnRouteCallbackState.Awaiting, store.capture().callbackState)
            assertFalse(store.capture().isEligibleForInPathLease())
        }
    }

    @Test
    fun `new network requires fresh capabilities and exact descriptor identity`() {
        val store = VpnRouteLifecycleReceiptStore()
        val generation = store.beginGeneration()
        store.markEstablished(generation, testRouteTunIdentity)
        store.markBridgeReady(generation)
        store.routes(testRouteTunIdentity)
        assertFalse(store.capture().isEligibleForInPathLease())
        store.verifiedCapabilities()
        assertTrue(store.capture().isEligibleForInPathLease())
        store.routes(PrivateTunIdentity.create("test0", 18))
        assertFalse(store.capture().isEligibleForInPathLease())
    }

    @Test
    fun `unreadable descriptor cannot admit even fully matching callbacks`() {
        val store = VpnRouteLifecycleReceiptStore()
        val generation = store.beginGeneration()
        store.markEstablished(generation, null)
        store.markBridgeReady(generation)
        store.verifiedCapabilities()
        store.routes(testRouteTunIdentity)
        assertFalse(store.capture().isEligibleForInPathLease())
    }

    @Test
    fun `unverified retained owner cannot admit replacement with matching LP`() {
        val store = VpnRouteLifecycleReceiptStore()
        val previous = store.beginGeneration()
        store.markEstablished(previous, testRouteTunIdentity)
        store.verifiedCapabilities(VpnRouteOwnerVerification.Unavailable)
        store.routes(testRouteTunIdentity)
        val replacement = store.beginGeneration()
        store.markEstablished(replacement, testRouteTunIdentity)
        store.markBridgeReady(replacement)
        store.verifiedCapabilities()
        store.routes(testRouteTunIdentity)
        assertFalse(store.capture().isEligibleForInPathLease())
    }

    @Test
    fun `owner rejection loss closed and fail closed cannot retain admission`() {
        listOf("reject", "lost", "closed", "fail_closed").forEach { termination ->
            val store = VpnRouteLifecycleReceiptStore()
            val generation = store.beginGeneration()
            store.markEstablished(generation, testRouteTunIdentity)
            store.verifiedCapabilities()
            store.routes(testRouteTunIdentity)
            store.markBridgeReady(generation)
            assertTrue(store.capture().isEligibleForInPathLease())
            when (termination) {
                "reject" -> store.discardObservation("vpn-agent")
                "lost" -> store.observeLost("vpn-agent")
                "closed" -> store.markEnded(generation, VpnRouteLifecycleState.Closed)
                else -> store.markEnded(generation, VpnRouteLifecycleState.FailClosed)
            }
            store.routes(testRouteTunIdentity)
            assertFalse(store.capture().isEligibleForInPathLease())
        }
    }

    @Test
    fun `private identity rejects unusable kernel indexes and redacts printable output`() {
        assertNull(PrivateTunIdentity.create(null, 17))
        assertNull(PrivateTunIdentity.create("", 17))
        assertNull(PrivateTunIdentity.create("test\u0000suffix", 17))
        assertNull(PrivateTunIdentity.create("test0", 0))
        assertNull(PrivateTunIdentity.create("test0", -1))
        assertEquals("PrivateTunIdentity(redacted)", testRouteTunIdentity.toString())
    }
}

private fun VpnRouteLifecycleReceiptStore.beginGeneration(): Long =
    beginIntended(
        ipv6Enabled = false,
        dns = "1.1.1.1",
        appRoutingPlan = VpnAppRoutingPlan.Disallow(setOf("com.poyka.ripdpi")),
        ownPackage = "com.poyka.ripdpi",
        networkParameters = VpnTunnelNetworkParameters(),
        apiLevel = Build.VERSION_CODES.R,
    )

private fun VpnRouteLifecycleReceiptStore.verifiedCapabilities(
    owner: VpnRouteOwnerVerification = VpnRouteOwnerVerification.Verified,
) {
    observeCapabilities("vpn-agent", true, true, false, owner)
}

private fun VpnRouteLifecycleReceiptStore.routes(identity: PrivateTunIdentity?) {
    observeDefaultRoutes("vpn-agent", setOf(VpnRouteFamilyIpv4), identity)
}
