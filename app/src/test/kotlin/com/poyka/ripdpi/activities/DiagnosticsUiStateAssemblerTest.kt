package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsUiStateAssemblerTest {
    @Test
    fun `buildCurrentServiceTelemetry synthesizes service sample for running runtime`() {
        val sample =
            buildCurrentServiceTelemetry(
                status = AppStatus.Running,
                mode = Mode.VPN,
                telemetry =
                    ServiceTelemetrySnapshot(
                        mode = Mode.VPN,
                        status = AppStatus.Running,
                        tunnelStats =
                            TunnelStats(
                                txPackets = 4,
                                txBytes = 1_024,
                                rxPackets = 7,
                                rxBytes = 2_048,
                            ),
                        updatedAt = 123L,
                    ),
                activeConnectionSession = null,
            )

        assertNotNull(sample)
        assertEquals("service-state:vpn:123", sample?.id)
        assertEquals("Running", sample?.connectionState)
        assertEquals("VPN", sample?.activeMode)
        assertEquals(4L, sample?.txPackets)
        assertEquals(7L, sample?.rxPackets)
    }

    @Test
    fun `buildCurrentServiceTelemetry stays null for halted idle runtime`() {
        val sample =
            buildCurrentServiceTelemetry(
                status = AppStatus.Halted,
                mode = Mode.VPN,
                telemetry = ServiceTelemetrySnapshot(),
                activeConnectionSession = null,
            )

        assertNull(sample)
    }
}
