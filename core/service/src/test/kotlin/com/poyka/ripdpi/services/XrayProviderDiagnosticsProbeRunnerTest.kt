package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.testing.FakeXrayNativeBridge
import com.poyka.ripdpi.data.xray.VpnProviderState
import com.poyka.ripdpi.data.xray.XrayProviderProbeKind
import com.poyka.ripdpi.data.xray.XrayProviderSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * User-triggered provider probe runner (decision 5): in-process probes only,
 * StatApi hard-gated to not-applicable, skipped when no provider running.
 */
class XrayProviderDiagnosticsProbeRunnerTest {
    private val baseSnapshot = XrayProviderSnapshot()

    @Test
    fun `skips all probes when provider is not running`() {
        val runner = XrayProviderDiagnosticsProbeRunner(FakeXrayNativeBridge())
        val report = runner.run(VpnProviderState.Stopped, baseSnapshot)
        assertTrue(report.probes.isEmpty())
    }

    @Test
    fun `runs version listener and wrapper-ping in-process when running`() {
        val bridge = FakeXrayNativeBridge()
        // Make the bridge "running" so listenerReady/isAlive report true.
        bridge.start("{}")
        val runner = XrayProviderDiagnosticsProbeRunner(bridge)
        val report = runner.run(VpnProviderState.Running, baseSnapshot)

        val kinds = report.probes.map { it.kind }.toSet()
        assertTrue(kinds.contains(XrayProviderProbeKind.Version))
        assertTrue(kinds.contains(XrayProviderProbeKind.ListenerReadiness))
        assertTrue(kinds.contains(XrayProviderProbeKind.WrapperPing))
        assertTrue(report.probes.first { it.kind == XrayProviderProbeKind.Version }.ok)
    }

    @Test
    fun `StatApi is always not-applicable and never invoked in-process`() {
        val bridge = FakeXrayNativeBridge()
        bridge.start("{}")
        val runner = XrayProviderDiagnosticsProbeRunner(bridge)
        val report = runner.run(VpnProviderState.Running, baseSnapshot)

        val statApi = report.probes.single { it.kind == XrayProviderProbeKind.StatApi }
        assertFalse("StatApi must report not-applicable as not-ok", statApi.ok)
        assertTrue(statApi.detailRedacted!!.contains("not applicable"))
    }

    @Test
    fun `listener-not-ready surfaces as a failed readiness probe`() {
        val bridge = FakeXrayNativeBridge(readyAfterPolls = 10)
        bridge.start("{}")
        val runner = XrayProviderDiagnosticsProbeRunner(bridge)
        val report = runner.run(VpnProviderState.Running, baseSnapshot)
        val listener = report.probes.single { it.kind == XrayProviderProbeKind.ListenerReadiness }
        assertFalse(listener.ok)
    }

    @Test
    fun `report carries the base snapshot unchanged`() {
        val bridge = FakeXrayNativeBridge()
        bridge.start("{}")
        val snapshot = XrayProviderSnapshot(profileName = "Tokyo")
        val report = XrayProviderDiagnosticsProbeRunner(bridge).run(VpnProviderState.Running, snapshot)
        assertEquals("Tokyo", report.snapshot.profileName)
    }
}
