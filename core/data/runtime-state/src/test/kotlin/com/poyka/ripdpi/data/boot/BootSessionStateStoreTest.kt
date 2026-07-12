package com.poyka.ripdpi.data.boot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BootSessionStateStoreTest {
    // The store takes a SharedPreferences directly; the device-protected (direct-boot)
    // context is supplied by DeviceProtectedBootPrefsModule in production. This exercises
    // the store's read/write/clear logic against an equivalent private prefs file.
    private val prefs =
        ApplicationProvider
            .getApplicationContext<Context>()
            .getSharedPreferences("ripdpi_boot_session_state_test", Context.MODE_PRIVATE)
            .also { it.edit().clear().apply() }

    private val store = SharedPreferencesBootSessionStateStore(prefs)

    @Test
    fun `lastSession is null before any session is recorded`() {
        assertNull(store.lastSession())
    }

    @Test
    fun `recordSession round-trips the profile id and mode`() {
        store.recordSession(profileId = "profile-42", mode = Mode.VPN)

        val pointer = store.lastSession()
        assertEquals(BootSessionPointer(profileId = "profile-42", mode = Mode.VPN), pointer)
    }

    @Test
    fun `recordSession persists proxy mode distinctly from vpn`() {
        store.recordSession(profileId = "p", mode = Mode.Proxy)

        assertEquals(Mode.Proxy, store.lastSession()?.mode)
    }

    @Test
    fun `recordSession overwrites the previous pointer`() {
        store.recordSession(profileId = "old", mode = Mode.Proxy)
        store.recordSession(profileId = "new", mode = Mode.VPN)

        assertEquals(BootSessionPointer(profileId = "new", mode = Mode.VPN), store.lastSession())
    }

    @Test
    fun `clear removes the recorded pointer so resume is skipped`() {
        store.recordSession(profileId = "p", mode = Mode.VPN)

        store.clear()

        assertNull(store.lastSession())
    }

    @Test
    fun `clearAll removes the pointer and update auto-resume marker`() {
        store.recordSession(profileId = "p", mode = Mode.VPN)
        store.setWasRunningAtUpdate(true)

        store.clearAll()

        assertNull(store.lastSession())
        assertFalse(store.wasRunningAtUpdate())
    }

    @Test
    fun `a pointer written before reboot is readable by a fresh store instance`() {
        // The same device-protected prefs file backs both instances, modelling a
        // pointer persisted during a prior session and read by a fresh post-boot process.
        store.recordSession(profileId = "survivor", mode = Mode.VPN)

        val afterReboot = SharedPreferencesBootSessionStateStore(prefs)

        assertEquals(
            BootSessionPointer(profileId = "survivor", mode = Mode.VPN),
            afterReboot.lastSession(),
        )
    }

    @Test
    fun `wasRunningAtUpdate defaults to false and round-trips`() {
        assertFalse(store.wasRunningAtUpdate())

        store.setWasRunningAtUpdate(true)
        assertTrue(store.wasRunningAtUpdate())

        store.setWasRunningAtUpdate(false)
        assertFalse(store.wasRunningAtUpdate())
    }

    @Test
    fun `wasRunningAtUpdate survives into a fresh store instance over the same prefs`() {
        // The flag gates auto-resume after an LMK kill, which gives no chance to flush
        // a deferred write — so the store commits (fsync) rather than apply()ing. This
        // locks the durability contract: a flag set in one session must be visible to
        // the fresh post-kill process reading the same device-protected prefs file.
        store.setWasRunningAtUpdate(true)

        val afterKill = SharedPreferencesBootSessionStateStore(prefs)

        assertTrue(afterKill.wasRunningAtUpdate())
    }

    @Test
    fun `a blank profile id round-trips without becoming a present-but-empty pointer`() {
        store.recordSession(profileId = "", mode = Mode.VPN)

        val pointer = store.lastSession()
        assertEquals("", pointer?.profileId)
        assertEquals(Mode.VPN, pointer?.mode)
    }
}
