package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUiStateTest {

    private val defaults = AppSettingsSerializer.defaultValue

    @Test
    fun `default settings produce correct ui state`() {
        val state = defaults.toUiState()
        assertTrue(state.isVpn)
        assertFalse(state.useCmdSettings)
        assertEquals("disorder", state.desyncMethod)
        assertTrue(state.desyncEnabled)
        assertFalse(state.isFake)
        assertFalse(state.isOob)
        assertTrue(state.desyncHttpEnabled)
        assertTrue(state.desyncHttpsEnabled)
        assertFalse(state.desyncUdpEnabled)
        assertFalse(state.tlsRecEnabled)
        assertFalse(state.webrtcProtectionEnabled)
        assertFalse(state.biometricEnabled)
        assertEquals("", state.backupPin)
    }

    @Test
    fun `proxy mode sets isVpn false`() {
        val settings = defaults.toBuilder().setRipdpiMode("proxy").build()
        assertFalse(settings.toUiState().isVpn)
    }

    @Test
    fun `empty mode defaults to vpn`() {
        val settings = defaults.toBuilder().setRipdpiMode("").build()
        assertTrue(settings.toUiState().isVpn)
    }

    @Test
    fun `desync method none disables desync`() {
        val settings = defaults.toBuilder().setDesyncMethod("none").build()
        val state = settings.toUiState()
        assertFalse(state.desyncEnabled)
        assertFalse(state.isFake)
        assertFalse(state.isOob)
    }

    @Test
    fun `desync method fake sets isFake`() {
        val settings = defaults.toBuilder().setDesyncMethod("fake").build()
        val state = settings.toUiState()
        assertTrue(state.isFake)
        assertTrue(state.desyncEnabled)
    }

    @Test
    fun `desync method oob sets isOob`() {
        val settings = defaults.toBuilder().setDesyncMethod("oob").build()
        assertTrue(settings.toUiState().isOob)
    }

    @Test
    fun `desync method disoob sets isOob`() {
        val settings = defaults.toBuilder().setDesyncMethod("disoob").build()
        assertTrue(settings.toUiState().isOob)
    }

    @Test
    fun `all protocols unchecked enables all`() {
        val settings = defaults.toBuilder()
            .setDesyncHttp(false)
            .setDesyncHttps(false)
            .setDesyncUdp(false)
            .build()
        val state = settings.toUiState()
        assertTrue(state.desyncHttpEnabled)
        assertTrue(state.desyncHttpsEnabled)
        assertTrue(state.desyncUdpEnabled)
    }

    @Test
    fun `only http checked enables only http`() {
        val settings = defaults.toBuilder()
            .setDesyncHttp(true)
            .setDesyncHttps(false)
            .setDesyncUdp(false)
            .build()
        val state = settings.toUiState()
        assertTrue(state.desyncHttpEnabled)
        assertFalse(state.desyncHttpsEnabled)
        assertFalse(state.desyncUdpEnabled)
    }

    @Test
    fun `tlsrec enabled only when https enabled and toggle on`() {
        val settings = defaults.toBuilder()
            .setDesyncHttps(true)
            .setTlsrecEnabled(true)
            .build()
        assertTrue(settings.toUiState().tlsRecEnabled)
    }

    @Test
    fun `tlsrec disabled when https disabled even if toggle on`() {
        val settings = defaults.toBuilder()
            .setDesyncHttp(true)
            .setDesyncHttps(false)
            .setDesyncUdp(false)
            .setTlsrecEnabled(true)
            .build()
        assertFalse(settings.toUiState().tlsRecEnabled)
    }

    @Test
    fun `cmd settings enabled`() {
        val settings = defaults.toBuilder().setEnableCmdSettings(true).build()
        assertTrue(settings.toUiState().useCmdSettings)
    }

    @Test
    fun `empty desync method defaults to disorder`() {
        val settings = defaults.toBuilder().setDesyncMethod("").build()
        assertEquals("disorder", settings.toUiState().desyncMethod)
    }
}
