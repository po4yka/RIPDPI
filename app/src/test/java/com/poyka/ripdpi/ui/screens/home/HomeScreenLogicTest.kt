package com.poyka.ripdpi.ui.screens.home

import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.data.Mode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeScreenLogicTest {

    @Test
    fun `opens permission interstitial when vpn permission is required for vpn mode`() {
        val uiState = MainUiState(
            configuredMode = Mode.VPN,
            connectionState = ConnectionState.Disconnected,
        )

        assertTrue(shouldOpenVpnPermission(uiState = uiState, vpnPermissionRequired = true))
    }

    @Test
    fun `does not open permission interstitial for proxy mode`() {
        val uiState = MainUiState(
            configuredMode = Mode.Proxy,
            connectionState = ConnectionState.Disconnected,
        )

        assertFalse(shouldOpenVpnPermission(uiState = uiState, vpnPermissionRequired = true))
    }

    @Test
    fun `does not open permission interstitial while already connected`() {
        val uiState = MainUiState(
            configuredMode = Mode.VPN,
            connectionState = ConnectionState.Connected,
        )

        assertFalse(shouldOpenVpnPermission(uiState = uiState, vpnPermissionRequired = true))
    }
}
