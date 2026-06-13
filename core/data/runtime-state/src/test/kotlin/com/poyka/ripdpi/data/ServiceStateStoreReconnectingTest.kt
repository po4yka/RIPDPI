package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lifecycle semantics of [AppStatus.Reconnecting] — the transient bring-up state
 * written by the boot-resume and LMK (sticky-restart) paths.
 */
class ServiceStateStoreReconnectingTest {
    @Test
    fun `a fresh store is Halted - process death never resurfaces a stale active state`() {
        // SIGKILL runs no Drop, so a restarted process constructs a brand-new store.
        // Its initial status MUST be Halted: no stale Running or Reconnecting can
        // survive a kill, because Reconnecting is only ever written by live resume code.
        val store = DefaultServiceStateStore()

        assertEquals(AppStatus.Halted, store.status.value.first)
    }

    @Test
    fun `Reconnecting does not start the uptime clock or count as a restart`() {
        val store = DefaultServiceStateStore()

        store.setStatus(AppStatus.Reconnecting, Mode.VPN)

        val telemetry = store.telemetry.value
        assertEquals(AppStatus.Reconnecting, store.status.value.first)
        assertNull("uptime must not accrue while merely reconnecting", telemetry.serviceStartedAt)
        assertEquals("Reconnecting is not yet a restart", 0, telemetry.restartCount)
    }

    @Test
    fun `the transition into Running counts the restart exactly once across a Reconnecting hop`() {
        val store = DefaultServiceStateStore()

        // Halted -> Reconnecting -> Running models a boot/LMK resume reaching Connected.
        store.setStatus(AppStatus.Reconnecting, Mode.VPN)
        store.setStatus(AppStatus.Running, Mode.VPN)

        val telemetry = store.telemetry.value
        assertEquals(AppStatus.Running, store.status.value.first)
        assertEquals(
            "the restart is counted once, on the Reconnecting -> Running edge — not twice",
            1,
            telemetry.restartCount,
        )
        assertEquals(
            "the uptime clock starts when Running is reached",
            telemetry.serviceStartedAt != null,
            true,
        )
    }

    @Test
    fun `a failed resume returns to Halted and clears the uptime clock`() {
        val store = DefaultServiceStateStore()

        store.setStatus(AppStatus.Reconnecting, Mode.Proxy)
        store.setStatus(AppStatus.Halted, Mode.Proxy)

        val telemetry = store.telemetry.value
        assertEquals(AppStatus.Halted, store.status.value.first)
        assertNull(telemetry.serviceStartedAt)
        assertEquals(0, telemetry.restartCount)
    }
}
