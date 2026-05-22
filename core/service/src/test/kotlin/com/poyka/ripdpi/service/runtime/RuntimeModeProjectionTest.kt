package com.poyka.ripdpi.service.runtime

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.services.ServiceLifecycleStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [RuntimeModeProjection] derivation from representative runtime states.
 *
 * The projection is purely derived — these tests confirm it re-presents the
 * inputs faithfully and never invents state it was not given (an unobserved
 * layer must stay `Unknown` / `null`).
 */
class RuntimeModeProjectionTest {
    @Test
    fun `halted proxy projects the halted status and the proxy mode`() {
        val projection = RuntimeModeProjection.from(AppStatus.Halted to Mode.Proxy)

        assertEquals(AppStatus.Halted, projection.appStatus)
        assertEquals(Mode.Proxy, projection.mode)
        assertEquals(RuntimeLayerState.Unknown, projection.relay)
        assertEquals(RuntimeLayerState.Unknown, projection.diagnosticsScan)
        assertEquals(RootHelperState.Unknown, projection.rootHelper)
        assertNull(projection.serviceStatus)
        assertNull(projection.lifecyclePhase)
    }

    @Test
    fun `running proxy projects the running status and the proxy mode`() {
        val projection = RuntimeModeProjection.from(AppStatus.Running to Mode.Proxy)

        assertEquals(AppStatus.Running, projection.appStatus)
        assertEquals(Mode.Proxy, projection.mode)
    }

    @Test
    fun `running VPN projects the running status and the VPN mode`() {
        val projection = RuntimeModeProjection.from(AppStatus.Running to Mode.VPN)

        assertEquals(AppStatus.Running, projection.appStatus)
        assertEquals(Mode.VPN, projection.mode)
    }

    @Test
    fun `relay enabled but not yet active projects an inactive relay layer`() {
        // "Enabled" is a setting; the relay layer reports only observed
        // activity. A configured-but-not-yet-connected relay is Inactive.
        val projection =
            RuntimeModeProjection.from(
                AppStatus.Running to Mode.VPN,
                relayActive = false,
            )

        assertEquals(RuntimeLayerState.Inactive, projection.relay)
    }

    @Test
    fun `relay active over the base mode projects an active relay layer`() {
        val overVpn =
            RuntimeModeProjection.from(
                AppStatus.Running to Mode.VPN,
                relayActive = true,
            )
        assertEquals(RuntimeLayerState.Active, overVpn.relay)

        val overProxy =
            RuntimeModeProjection.from(
                AppStatus.Running to Mode.Proxy,
                relayActive = true,
            )
        assertEquals(RuntimeLayerState.Active, overProxy.relay)
    }

    @Test
    fun `diagnostics scan active projects an active diagnostics layer`() {
        val projection =
            RuntimeModeProjection.from(
                AppStatus.Running to Mode.Proxy,
                diagnosticsScanActive = true,
            )

        assertEquals(RuntimeLayerState.Active, projection.diagnosticsScan)
    }

    @Test
    fun `root helper disabled projects the disabled root state`() {
        // The non-root baseline: root mode provably off is distinct from
        // "not observed".
        val projection =
            RuntimeModeProjection.from(
                AppStatus.Running to Mode.VPN,
                rootModeEnabled = false,
                rootHelperSocketAvailable = true,
            )

        assertEquals(RootHelperState.Disabled, projection.rootHelper)
    }

    @Test
    fun `root helper enabled but unavailable projects enabled-unavailable`() {
        val socketDown =
            RuntimeModeProjection.from(
                AppStatus.Running to Mode.VPN,
                rootModeEnabled = true,
                rootHelperSocketAvailable = false,
            )
        assertEquals(RootHelperState.EnabledUnavailable, socketDown.rootHelper)

        // Root mode on, socket reachability not observed: still "enabled, not
        // confirmed available".
        val socketUnobserved =
            RuntimeModeProjection.from(
                AppStatus.Running to Mode.VPN,
                rootModeEnabled = true,
                rootHelperSocketAvailable = null,
            )
        assertEquals(RootHelperState.EnabledUnavailable, socketUnobserved.rootHelper)
    }

    @Test
    fun `root helper enabled and socket available projects enabled-available`() {
        val projection =
            RuntimeModeProjection.from(
                AppStatus.Running to Mode.VPN,
                rootModeEnabled = true,
                rootHelperSocketAvailable = true,
            )

        assertEquals(RootHelperState.EnabledAvailable, projection.rootHelper)
    }

    @Test
    fun `root helper stays unknown when the root-mode setting is not observed`() {
        val projection =
            RuntimeModeProjection.from(
                AppStatus.Running to Mode.VPN,
                rootModeEnabled = null,
                rootHelperSocketAvailable = true,
            )

        assertEquals(RootHelperState.Unknown, projection.rootHelper)
    }

    @Test
    fun `unobserved relay and diagnostics signals map to the unknown layer state`() {
        val projection =
            RuntimeModeProjection.from(
                AppStatus.Running to Mode.VPN,
                relayActive = null,
                diagnosticsScanActive = null,
            )

        assertEquals(RuntimeLayerState.Unknown, projection.relay)
        assertEquals(RuntimeLayerState.Unknown, projection.diagnosticsScan)
    }

    @Test
    fun `observed service status and lifecycle phase pass through unchanged`() {
        val projection =
            RuntimeModeProjection.from(
                AppStatus.Running to Mode.VPN,
                serviceStatus = ServiceStatus.Connected,
                lifecyclePhase = ServiceLifecycleStateMachine.State.RUNNING,
            )

        assertEquals(ServiceStatus.Connected, projection.serviceStatus)
        assertEquals(ServiceLifecycleStateMachine.State.RUNNING, projection.lifecyclePhase)
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

        assertEquals(AppStatus.Halted, projection.appStatus)
        assertEquals(RuntimeLayerState.Inactive, projection.relay)
        assertEquals(RuntimeLayerState.Active, projection.diagnosticsScan)
    }

    @Test
    fun `a fully observed running VPN state projects every field`() {
        val projection =
            RuntimeModeProjection.from(
                status = AppStatus.Running to Mode.VPN,
                serviceStatus = ServiceStatus.Connected,
                lifecyclePhase = ServiceLifecycleStateMachine.State.RUNNING,
                relayActive = true,
                diagnosticsScanActive = false,
                rootModeEnabled = true,
                rootHelperSocketAvailable = true,
            )

        assertEquals(
            RuntimeModeProjection(
                appStatus = AppStatus.Running,
                mode = Mode.VPN,
                serviceStatus = ServiceStatus.Connected,
                lifecyclePhase = ServiceLifecycleStateMachine.State.RUNNING,
                relay = RuntimeLayerState.Active,
                diagnosticsScan = RuntimeLayerState.Inactive,
                rootHelper = RootHelperState.EnabledAvailable,
            ),
            projection,
        )
    }
}
