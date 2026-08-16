package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NetworkPathAssociationServiceBinder
import com.poyka.ripdpi.data.NetworkPathAssociationUnknown
import com.poyka.ripdpi.data.NetworkPathObservation
import com.poyka.ripdpi.data.VpnRouteAppRoutingShape
import com.poyka.ripdpi.data.VpnRouteCallbackState
import com.poyka.ripdpi.data.VpnRouteConsistency
import com.poyka.ripdpi.data.VpnRouteEvidence
import com.poyka.ripdpi.data.VpnRouteLifecycleReceipt
import com.poyka.ripdpi.data.VpnRouteLifecycleState
import com.poyka.ripdpi.data.VpnRouteOwnerVerification
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPathValidationEvidenceTest {
    @Test
    fun `legacy path evidence decodes without owned vpn provenance`() {
        val encoded =
            requireNotNull(javaClass.classLoader?.getResource("fixtures/network_snapshot_schema_v9.json"))
                .readText()
        val decoded = diagnosticsTestJson().decodeFromString<NetworkSnapshotModel>(encoded)

        assertFalse(encoded.contains("vpnRouteEvidence"))
        assertFalse(requireNotNull(decoded.pathValidation?.vpnPresent))
        assertNull(decoded.pathValidation?.vpnRouteEvidence)
        assertEquals("unavailable", decoded.pathValidation?.callingDefaultObserverRole)
        assertEquals(7L, decoded.pathSnapshots?.captureGeneration)
    }

    @Test
    fun `route projection rejects values outside the privacy allowlist`() {
        val sentinel = "private.example/uid-123/address-192.0.2.1"
        val projected =
            VpnRouteEvidence(
                observerRole = sentinel,
                observerSource = sentinel,
                evidenceAgeBand = sentinel,
                observedDefaultRouteFamilies = listOf("ipv4", sentinel),
                forwardingOutcome = sentinel,
            ).toDiagnosticsSnapshot()

        val encoded = diagnosticsTestJson().encodeToString(projected)
        val unsafePersistedSummary =
            networkSnapshotModelForTest()
                .copy(
                    pathValidation =
                        NetworkPathValidationEvidence(
                            captureStatus = "captured",
                            vpnRouteEvidence =
                                VpnRouteEvidenceSnapshot(
                                    observerRole = sentinel,
                                    observerSource = sentinel,
                                    lifecycleState = sentinel,
                                    callbackState = sentinel,
                                    evidenceAgeBand = sentinel,
                                    observedDefaultRouteFamilies = listOf(sentinel),
                                    forwardingOutcome = sentinel,
                                ),
                        ),
                ).toRedactedSummary()
        val persistedEncoded = diagnosticsTestJson().encodeToString(unsafePersistedSummary)

        assertFalse(encoded.contains(sentinel))
        assertFalse(persistedEncoded.contains(sentinel))
        assertEquals("unavailable", projected.observerRole)
        assertEquals("unavailable", projected.observerSource)
        assertEquals("unknown", projected.evidenceAgeBand)
        assertEquals(listOf("ipv4"), projected.observedDefaultRouteFamilies)
        assertEquals("unavailable", projected.forwardingOutcome)
    }

    @Test
    fun `owned vpn route evidence remains authoritative when caller default is underlay`() {
        val routeEvidence =
            VpnRouteEvidence(
                lifecycle =
                    VpnRouteLifecycleReceipt(
                        generation = 9L,
                        state = VpnRouteLifecycleState.BridgeReady,
                        intendedDefaultRouteFamilies = listOf("ipv4"),
                        addressFamilies = listOf("ipv4"),
                        dnsServerFamilies = listOf("ipv4"),
                        appRoutingShape = VpnRouteAppRoutingShape.Disallow,
                        configuredAppCount = 1,
                        ownPackageExcluded = true,
                        ipv6Intent = false,
                        mtuBand = "standard",
                        metered = null,
                        appliedTunnelReceiptGeneration = 4L,
                    ),
                callbackState = VpnRouteCallbackState.Complete,
                callbackRevision = 3L,
                ownerVerification = VpnRouteOwnerVerification.Verified,
                observedDefaultRouteFamilies = listOf("ipv4"),
                routeConsistency = VpnRouteConsistency.Consistent,
                vpnPresent = true,
                hasInternet = true,
                validated = true,
                captivePortal = false,
                forwardingOutcome = "cross_layer_return_observed",
                forwardingLifecycleGeneration = 9L,
                forwardingTerminal = true,
            )

        val evidence =
            resolvePathValidationEvidence(
                permissionAvailable = true,
                activePath = wifi(validated = true),
                underlay = underlay(validated = true),
                vpnRouteEvidence = routeEvidence.toDiagnosticsSnapshot(),
            )

        assertFalse(requireNotNull(evidence.vpnPresent))
        assertEquals("calling_uid_default", evidence.callingDefaultObserverRole)
        val projectedRoute = requireNotNull(evidence.vpnRouteEvidence)
        assertEquals("vpn_owner_service", projectedRoute.observerRole)
        assertEquals("bridge_ready", projectedRoute.lifecycleState)
        assertEquals("complete", projectedRoute.callbackState)
        assertEquals("consistent", projectedRoute.routeConsistency)
        assertTrue(requireNotNull(projectedRoute.vpnPresent))
        assertEquals("disallow", projectedRoute.appRoutingShape)
        assertEquals(1, projectedRoute.configuredAppCount)
        assertEquals(4L, projectedRoute.appliedTunnelReceiptGeneration)
        assertEquals("cross_layer_return_observed", projectedRoute.forwardingOutcome)
        assertEquals(9L, projectedRoute.forwardingLifecycleGeneration)
        assertEquals(true, projectedRoute.forwardingTerminal)
    }

    @Test
    fun `validated vpn and underlay are reported independently`() {
        val evidence =
            resolvePathValidationEvidence(
                permissionAvailable = true,
                activePath = vpn(validated = true),
                underlay = underlay(validated = true),
            )

        assertEquals("captured", evidence.captureStatus)
        assertEquals("wifi", evidence.underlayTransport)
        assertTrue(requireNotNull(evidence.underlayValidated))
        assertTrue(requireNotNull(evidence.vpnValidated))
    }

    @Test
    fun `unvalidated vpn does not erase validated underlay`() {
        val evidence =
            resolvePathValidationEvidence(
                permissionAvailable = true,
                activePath = vpn(validated = false),
                underlay = underlay(validated = true),
            )

        assertTrue(requireNotNull(evidence.underlayValidated))
        assertFalse(requireNotNull(evidence.vpnValidated))
    }

    @Test
    fun `absent vpn remains distinct from failed vpn validation`() {
        val evidence =
            resolvePathValidationEvidence(
                permissionAvailable = true,
                activePath = wifi(validated = true),
                underlay = underlay(validated = true),
            )

        assertFalse(requireNotNull(evidence.vpnPresent))
        assertNull(evidence.vpnValidated)
    }

    @Test
    fun `missing permission reports unknown path state`() {
        val evidence =
            resolvePathValidationEvidence(
                permissionAvailable = false,
                activePath = null,
                underlay = underlay(validated = true),
            )

        assertEquals("permission_unavailable", evidence.captureStatus)
        assertNull(evidence.underlayPresent)
        assertNull(evidence.vpnPresent)
    }

    @Test
    fun `arbitrary non vpn path is never reported as authoritative underlay`() {
        val evidence =
            resolvePathValidationEvidence(
                permissionAvailable = true,
                activePath = wifi(validated = true),
                underlay = NetworkPathObservation(),
            )

        assertEquals(NetworkPathAssociationUnknown, evidence.underlayAssociation)
        assertNull(evidence.underlayPresent)
        assertFalse(requireNotNull(evidence.vpnPresent))
    }

    @Test
    fun `legacy snapshot serialization omits absent additive evidence`() {
        val encoded = diagnosticsTestJson().encodeToString(networkSnapshotModelForTest())

        assertFalse(encoded.contains("pathValidation"))
        assertFalse(encoded.contains("pathSnapshots"))
    }

    @Test
    fun `redacted archive summary retains privacy-safe path evidence`() {
        val evidence =
            resolvePathValidationEvidence(
                permissionAvailable = true,
                activePath = vpn(validated = false),
                underlay = underlay(validated = true),
            )

        val pair =
            NetworkPathSnapshotPair(
                captureGeneration = 2L,
                vpn = NetworkPathObservation(),
                underlay = underlay(validated = true),
            )
        val summary =
            networkSnapshotModelForTest()
                .copy(
                    pathValidation = evidence,
                    pathSnapshots = pair,
                ).toRedactedSummary()

        assertEquals(evidence, summary.pathValidation)
        assertEquals(pair, summary.pathSnapshots)
    }

    private fun wifi(validated: Boolean) =
        NetworkPathCapabilities(
            transport = "wifi",
            isVpn = false,
            hasInternet = true,
            validated = validated,
            captivePortal = false,
        )

    private fun vpn(validated: Boolean) =
        NetworkPathCapabilities(
            transport = "vpn",
            isVpn = true,
            hasInternet = true,
            validated = validated,
            captivePortal = false,
        )

    private fun underlay(validated: Boolean) =
        NetworkPathObservation(
            association = NetworkPathAssociationServiceBinder,
            generation = 7L,
            transport = "wifi",
            hasInternet = true,
            validated = validated,
            captivePortal = false,
        )
}
