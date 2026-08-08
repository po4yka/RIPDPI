package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the AppStatus.Reconnecting branches added to the home-screen resolvers,
 * which drive what the user sees during a boot/LMK resume.
 */
class MainStateResolversReconnectingTest {
    @Test
    fun `reconnecting surfaces as connecting while the runtime is not yet connected`() {
        assertEquals(
            ConnectionState.Connecting,
            resolveEffectiveConnectionState(AppStatus.Reconnecting, ConnectionState.Disconnected),
        )
        assertEquals(
            ConnectionState.Connecting,
            resolveEffectiveConnectionState(AppStatus.Reconnecting, ConnectionState.Error),
        )
    }

    @Test
    fun `reconnecting yields to a runtime that has already connected`() {
        assertEquals(
            ConnectionState.Connected,
            resolveEffectiveConnectionState(AppStatus.Reconnecting, ConnectionState.Connected),
        )
    }

    @Test
    fun `primary action during reconnect stops via the effective connecting state`() {
        // The real path: appStatus Reconnecting -> effective Connecting -> STOP (cancel).
        assertEquals(
            MainPrimaryConnectionAction.STOP,
            resolvePrimaryConnectionAction(ConnectionState.Connecting, AppStatus.Reconnecting),
        )
    }

    @Test
    fun `reconnecting never resolves to a start action even on the defensive disconnected path`() {
        // Exhaustiveness/defensive arm: must group with Running (STOP), never START.
        assertEquals(
            MainPrimaryConnectionAction.STOP,
            resolvePrimaryConnectionAction(ConnectionState.Disconnected, AppStatus.Reconnecting),
        )
        assertEquals(
            MainPrimaryConnectionAction.STOP,
            resolvePrimaryConnectionAction(ConnectionState.Error, AppStatus.Reconnecting),
        )
    }
}
