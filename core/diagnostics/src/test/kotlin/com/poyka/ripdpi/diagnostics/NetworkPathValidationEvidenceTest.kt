package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NetworkPathAssociationServiceBinder
import com.poyka.ripdpi.data.NetworkPathAssociationUnknown
import com.poyka.ripdpi.data.NetworkPathObservation
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPathValidationEvidenceTest {
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
