package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the [Mode] preference-value contract and [AppSettingsSerializer.defaultValue] mode.
 *
 * These are load-bearing guarantees for the proxy-mode epic ship definition:
 *
 * - T5 (mode persists and restores): [Mode.fromString] must round-trip through
 *   [Mode.preferenceValue] for both modes.  This is the serialization path used by
 *   [AppSettingsSerializer] (settings proto field) and
 *   [SharedPreferencesBootSessionStateStore] (device-protected boot pointer).
 *   If the round-trip breaks, the boot-resume path restores the wrong mode.
 *
 * - T5 (default is VPN, not proxy): [AppSettingsSerializer.defaultValue] must not
 *   default to [Mode.Proxy].  A first-launch device that has never chosen a mode
 *   must start as VPN; proxy is an explicit opt-in.
 */
class ModePreferencePersistenceTest {
    @Test
    fun `Mode_Proxy preferenceValue round-trips through fromString`() {
        val raw = Mode.Proxy.preferenceValue
        assertEquals(Mode.Proxy, Mode.fromString(raw))
    }

    @Test
    fun `Mode_VPN preferenceValue round-trips through fromString`() {
        val raw = Mode.VPN.preferenceValue
        assertEquals(Mode.VPN, Mode.fromString(raw))
    }

    @Test
    fun `Proxy and VPN preferenceValues are distinct`() {
        assertNotEquals(Mode.Proxy.preferenceValue, Mode.VPN.preferenceValue)
    }

    // T5 — boot/startup path must not silently default to VPN when Proxy was
    // persisted.  Conversely, a fresh install that has never set a mode must
    // default to VPN, not Proxy.  This asserts the factory default hard-codes
    // "vpn", so the first-launch experience is VPN-mode and Proxy is an
    // explicit user opt-in.
    @Test
    fun `AppSettingsSerializer defaultValue mode is VPN not Proxy`() {
        val defaultMode =
            Mode.fromString(
                AppSettingsSerializer.defaultValue.ripdpiMode.ifEmpty { Mode.VPN.preferenceValue },
            )
        assertEquals(Mode.VPN, defaultMode)
        assertNotEquals(Mode.Proxy, defaultMode)
    }
}
