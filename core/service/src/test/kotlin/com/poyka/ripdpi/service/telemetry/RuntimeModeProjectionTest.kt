package com.poyka.ripdpi.service.telemetry

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [RuntimeModeProjection] derivation from representative runtime states.
 *
 * The projection is purely derived — these tests confirm it re-presents the
 * inputs faithfully and never invents state it was not given (an unobserved
 * layer must stay `Unknown`).
 */
class RuntimeModeProjectionTest {
    @Test
    fun `running VPN with no observed layers projects the mode and leaves layers unknown`() {
        val projection = RuntimeModeProjection.from(AppStatus.Running to Mode.VPN)

        assertEquals(AppStatus.Running, projection.status)
        assertEquals(Mode.VPN, projection.activeMode)
        assertEquals(RuntimeLayerState.Unknown, projection.relay)
        assertEquals(RuntimeLayerState.Unknown, projection.diagnosticsScan)
        assertEquals(RootHelperState.Unknown, projection.rootHelper)
    }

    @Test
    fun `running proxy infers proxy as the active mode`() {
        val projection = RuntimeModeProjection.from(AppStatus.Running to Mode.Proxy)

        assertEquals(Mode.Proxy, projection.activeMode)
    }

    @Test
    fun `halted state leaves the active mode uninferable`() {
        // ServiceStateStore.status always carries a Mode; while halted it is
        // only the last/default mode, not the mode of a running runtime.
        for (mode in Mode.entries) {
            val projection = RuntimeModeProjection.from(AppStatus.Halted to mode)

            assertEquals(AppStatus.Halted, projection.status)
            assertNull("halted state carries no active mode (was $mode)", projection.activeMode)
        }
    }

    @Test
    fun `relay and diagnostics signals map to the tristate layer state`() {
        val active =
            RuntimeModeProjection.from(
                AppStatus.Running to Mode.VPN,
                relayActive = true,
                diagnosticsScanActive = true,
            )
        assertEquals(RuntimeLayerState.Active, active.relay)
        assertEquals(RuntimeLayerState.Active, active.diagnosticsScan)

        val inactive =
            RuntimeModeProjection.from(
                AppStatus.Running to Mode.VPN,
                relayActive = false,
                diagnosticsScanActive = false,
            )
        assertEquals(RuntimeLayerState.Inactive, inactive.relay)
        assertEquals(RuntimeLayerState.Inactive, inactive.diagnosticsScan)

        val unobserved =
            RuntimeModeProjection.from(
                AppStatus.Running to Mode.VPN,
                relayActive = null,
                diagnosticsScanActive = null,
            )
        assertEquals(RuntimeLayerState.Unknown, unobserved.relay)
        assertEquals(RuntimeLayerState.Unknown, unobserved.diagnosticsScan)
    }

    @Test
    fun `root helper is available only when root mode is on and the socket is up`() {
        val projection =
            RuntimeModeProjection.from(
                AppStatus.Running to Mode.VPN,
                rootModeEnabled = true,
                rootHelperSocketAvailable = true,
            )

        assertEquals(RootHelperState.Available, projection.rootHelper)
    }

    @Test
    fun `root helper is enabled when root mode is on but the socket is not up`() {
        val socketDown =
            RuntimeModeProjection.from(
                AppStatus.Running to Mode.VPN,
                rootModeEnabled = true,
                rootHelperSocketAvailable = false,
            )
        assertEquals(RootHelperState.Enabled, socketDown.rootHelper)

        val socketUnobserved =
            RuntimeModeProjection.from(
                AppStatus.Running to Mode.VPN,
                rootModeEnabled = true,
                rootHelperSocketAvailable = null,
            )
        assertEquals(RootHelperState.Enabled, socketUnobserved.rootHelper)
    }

    @Test
    fun `root helper stays unknown when root mode is off or unobserved`() {
        // The non-root baseline: root mode off => there is no root-helper layer.
        val rootModeOff =
            RuntimeModeProjection.from(
                AppStatus.Running to Mode.VPN,
                rootModeEnabled = false,
                rootHelperSocketAvailable = true,
            )
        assertEquals(RootHelperState.Unknown, rootModeOff.rootHelper)

        val rootModeUnobserved =
            RuntimeModeProjection.from(
                AppStatus.Running to Mode.VPN,
                rootModeEnabled = null,
            )
        assertEquals(RootHelperState.Unknown, rootModeUnobserved.rootHelper)
    }

    @Test
    fun `a fully observed running VPN state projects every layer`() {
        val projection =
            RuntimeModeProjection.from(
                status = AppStatus.Running to Mode.VPN,
                relayActive = true,
                diagnosticsScanActive = false,
                rootModeEnabled = true,
                rootHelperSocketAvailable = true,
            )

        assertEquals(
            RuntimeModeProjection(
                status = AppStatus.Running,
                activeMode = Mode.VPN,
                relay = RuntimeLayerState.Active,
                diagnosticsScan = RuntimeLayerState.Inactive,
                rootHelper = RootHelperState.Available,
            ),
            projection,
        )
    }

    @Test
    fun `layers are inferred independently of the base service status`() {
        // A raw-path diagnostics scan can run while the proxy/VPN runtime is
        // halted, so layer state must not be gated on AppStatus.
        val projection =
            RuntimeModeProjection.from(
                status = AppStatus.Halted to Mode.Proxy,
                relayActive = false,
                diagnosticsScanActive = true,
            )

        assertEquals(AppStatus.Halted, projection.status)
        assertNull(projection.activeMode)
        assertEquals(RuntimeLayerState.Inactive, projection.relay)
        assertEquals(RuntimeLayerState.Active, projection.diagnosticsScan)
    }
}
