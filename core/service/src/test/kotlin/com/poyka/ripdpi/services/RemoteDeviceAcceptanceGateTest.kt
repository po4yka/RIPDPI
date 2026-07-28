package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayKindVlessReality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDeviceAcceptanceGateTest {
    @Test
    fun `successful baseline passes data plane and leaves guided checks incomplete`() {
        val report = buildRemoteDeviceAcceptanceBaseline(Device, successfulEvidence())

        assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, report.status)
        assertTrue(report.steps.take(5).all { it.status == RemoteDeviceAcceptanceStatus.Pass })
        assertTrue(
            report.steps
                .drop(5)
                .take(3)
                .all { it.status == RemoteDeviceAcceptanceStatus.Incomplete },
        )
        assertEquals(RemoteDeviceAcceptanceStatus.Pass, report.steps.last().status)
        assertTrue(report.steps.filter { it.status == RemoteDeviceAcceptanceStatus.Pass }.all { it.errorClass == null })
    }

    @Test
    fun `DNS response failure proves association but fails UDP DNS`() {
        val evidence =
            successfulEvidence().copy(
                probe =
                    successfulProbe().copy(
                        udpSucceeded = false,
                        udpFailure = RelayProbeFailure.DnsResponse.wireValue,
                    ),
            )

        val report = buildRemoteDeviceAcceptanceBaseline(Device, evidence)

        assertEquals(RemoteDeviceAcceptanceStatus.Pass, report.step("socks_udp_associate").status)
        assertEquals(RemoteDeviceAcceptanceStatus.Fail, report.step("dns_udp").status)
        assertEquals(RelayProbeFailure.DnsResponse.wireValue, report.step("dns_udp").errorClass)
    }

    @Test
    fun `wrong transport fails closed without probe details`() {
        val report =
            buildRemoteDeviceAcceptanceBaseline(
                Device,
                successfulEvidence().copy(transportKind = "hysteria2", probe = null),
            )

        assertEquals(RemoteDeviceAcceptanceStatus.Fail, report.status)
        assertEquals("transport_mismatch", report.step("reality_tcp").errorClass)
        assertEquals("transport_mismatch", report.step("socks_udp_associate").errorClass)
        assertEquals("transport_mismatch", report.step("dns_udp").errorClass)
    }

    @Test
    fun `redacted report contains only approved device transport and step fields`() {
        val report = buildRemoteDeviceAcceptanceBaseline(Device, successfulEvidence())

        val rendered = renderRemoteDeviceAcceptanceReport(report)

        assertTrue(rendered.contains("ripdpi_remote_device_acceptance_v1"))
        assertTrue(rendered.contains("SM-S928B"))
        assertTrue(rendered.contains("XSG"))
        assertTrue(rendered.contains(RelayKindVlessReality))
        assertTrue(rendered.contains("durationMs"))
        assertFalse(rendered.contains("profile", ignoreCase = true))
        assertFalse(rendered.contains("credential", ignoreCase = true))
        assertFalse(rendered.contains("endpoint", ignoreCase = true))
        assertFalse(rendered.contains("uuid", ignoreCase = true))
        assertFalse(rendered.contains("network-hash", ignoreCase = true))
        assertNull(report.step("reality_tcp").errorClass)
    }

    private fun RemoteDeviceAcceptanceReport.step(id: String): RemoteDeviceAcceptanceStep = steps.first { it.id == id }

    private fun successfulEvidence(): AcceptanceBaselineEvidence =
        AcceptanceBaselineEvidence(
            serviceRunning = true,
            transportKind = RelayKindVlessReality,
            listenerAvailable = true,
            probe = successfulProbe(),
            ipv4Route = true,
            ipv6Route = true,
            directEgressObserved = false,
            durationMs = 42L,
        )

    private fun successfulProbe(): RelayCapabilityProbeEvidence =
        RelayCapabilityProbeEvidence(
            tcpSucceeded = true,
            tcpStatusCode = 204,
            tcpFailure = null,
            udpSucceeded = true,
            udpFailure = null,
            latencyMs = 42L,
        )

    private companion object {
        val Device =
            RemoteDeviceAcceptanceDevice(
                model = "SM-S928B",
                csc = "XSG",
                api = 35,
                abi = "arm64-v8a",
            )
    }
}
