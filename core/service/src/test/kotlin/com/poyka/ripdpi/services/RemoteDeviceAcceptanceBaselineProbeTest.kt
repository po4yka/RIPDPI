package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDeviceAcceptanceBaselineProbeTest {
    @Test
    fun `family steps use Reality egress probes instead of app active network`() =
        runTest {
            val requestedUrls = mutableListOf<String>()
            val capabilityProbe =
                RelayCapabilityProbe(
                    tcpProbe =
                        RelayTcpProbe { _, url ->
                            requestedUrls += url
                            if (url == RemoteAcceptanceIpv6ProbeUrl) {
                                RelayTcpProbeResult(
                                    succeeded = false,
                                    failure = RelayProbeFailure.TcpConnect,
                                )
                            } else {
                                RelayTcpProbeResult(succeeded = true, statusCode = 204)
                            }
                        },
                    udpProbe = RelayUdpAssociateProbe { RelayUdpProbeResult.success() },
                )
            val probe =
                RemoteDeviceAcceptanceBaselineProbe(
                    serviceStateStore = TestServiceStateStore(AppStatus.Running to Mode.VPN),
                    relayCapabilityProbe = capabilityProbe,
                    deviceProvider = { Device },
                    monotonicClock = { 1_000L },
                )

            val report = probe.capture(runningRealitySnapshot())

            assertEquals(
                setOf(
                    RemoteAcceptanceConnectivityProbeUrl,
                    RemoteAcceptanceIpv4ProbeUrl,
                    RemoteAcceptanceIpv6ProbeUrl,
                ),
                requestedUrls.toSet(),
            )
            assertEquals(RemoteDeviceAcceptanceStatus.Pass, report.step(StepIpv4).status)
            assertEquals(RemoteDeviceAcceptanceStatus.Fail, report.step(StepIpv6).status)
            assertEquals(RelayProbeFailure.TcpConnect.wireValue, report.step(StepIpv6).errorClass)
            assertTrue(report.steps.none { it.id.contains("route") })
        }

    private fun runningRealitySnapshot(): ServiceTelemetrySnapshot =
        ServiceTelemetrySnapshot(
            status = AppStatus.Running,
            mode = Mode.VPN,
            relayTelemetry =
                NativeRuntimeSnapshot(
                    source = "relay",
                    state = "running",
                    health = "healthy",
                    listenerAddress = "127.0.0.1:1080",
                    protocolKind = RelayKindVlessReality,
                ),
        )

    private fun RemoteDeviceAcceptanceReport.step(id: String): RemoteDeviceAcceptanceStep = steps.first { it.id == id }

    private companion object {
        val Device = RemoteDeviceAcceptanceDevice("SM-S928B", "XSG", 35, "arm64-v8a")
    }
}
