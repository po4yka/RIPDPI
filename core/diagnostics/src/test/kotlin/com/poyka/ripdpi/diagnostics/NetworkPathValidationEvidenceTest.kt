package com.poyka.ripdpi.diagnostics

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
                networks = listOf(wifi(validated = true), vpn(validated = true)),
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
                networks = listOf(wifi(validated = true), vpn(validated = false)),
            )

        assertTrue(requireNotNull(evidence.underlayValidated))
        assertFalse(requireNotNull(evidence.vpnValidated))
    }

    @Test
    fun `absent vpn remains distinct from failed vpn validation`() {
        val evidence =
            resolvePathValidationEvidence(
                permissionAvailable = true,
                networks = listOf(wifi(validated = true)),
            )

        assertFalse(requireNotNull(evidence.vpnPresent))
        assertNull(evidence.vpnValidated)
    }

    @Test
    fun `missing permission reports unknown path state`() {
        val evidence =
            resolvePathValidationEvidence(
                permissionAvailable = false,
                networks = emptyList(),
            )

        assertEquals("permission_unavailable", evidence.captureStatus)
        assertNull(evidence.underlayPresent)
        assertNull(evidence.vpnPresent)
    }

    @Test
    fun `empty observed network set reports both paths absent`() {
        val evidence =
            resolvePathValidationEvidence(
                permissionAvailable = true,
                networks = emptyList(),
            )

        assertFalse(requireNotNull(evidence.underlayPresent))
        assertFalse(requireNotNull(evidence.vpnPresent))
    }

    @Test
    fun `legacy snapshot serialization omits absent additive evidence`() {
        val encoded = diagnosticsTestJson().encodeToString(networkSnapshotModelForTest())

        assertFalse(encoded.contains("pathValidation"))
    }

    @Test
    fun `redacted archive summary retains privacy-safe path evidence`() {
        val evidence =
            resolvePathValidationEvidence(
                permissionAvailable = true,
                networks = listOf(wifi(validated = true), vpn(validated = false)),
            )

        val summary = networkSnapshotModelForTest().copy(pathValidation = evidence).toRedactedSummary()

        assertEquals(evidence, summary.pathValidation)
    }

    private fun wifi(validated: Boolean) =
        NetworkPathCapabilities(
            transport = "wifi",
            isVpn = false,
            isNotVpn = true,
            hasInternet = true,
            validated = validated,
            captivePortal = false,
        )

    private fun vpn(validated: Boolean) =
        NetworkPathCapabilities(
            transport = "vpn",
            isVpn = true,
            isNotVpn = false,
            hasInternet = true,
            validated = validated,
            captivePortal = false,
        )
}
