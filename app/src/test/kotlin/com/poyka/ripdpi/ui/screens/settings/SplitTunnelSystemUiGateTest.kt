package com.poyka.ripdpi.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelSystemUiGateTest {
    @Test
    fun `delegates to the system screen only on android 17 and above`() {
        // Android 16 (API 36) keeps the in-app editor; 17 (API 37) and later delegate to the OS screen.
        assertFalse(SplitTunnelSystemUiGate.usesSystemScreen(sdkInt = 36))
        assertTrue(SplitTunnelSystemUiGate.usesSystemScreen(sdkInt = 37))
        assertTrue(SplitTunnelSystemUiGate.usesSystemScreen(sdkInt = 40))
    }

    @Test
    fun `exclusion action matches the documented system intent`() {
        assertEquals(
            "android.settings.VPN_APP_EXCLUSION_SETTINGS",
            SplitTunnelSystemUiGate.ACTION_VPN_APP_EXCLUSION_SETTINGS,
        )
    }
}
