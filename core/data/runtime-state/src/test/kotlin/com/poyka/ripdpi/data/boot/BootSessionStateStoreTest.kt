package com.poyka.ripdpi.data.boot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `a pointer written before unlock is readable by a fresh store instance`() {
        // The same device-protected prefs file backs both instances, modelling the
        // direct-boot read path: a pointer persisted during a prior (post-unlock)
        // session is visible to a fresh process at LOCKED_BOOT_COMPLETED.
        store.recordSession(profileId = "survivor", mode = Mode.VPN)

        val afterReboot = SharedPreferencesBootSessionStateStore(prefs)

        assertEquals(
            BootSessionPointer(profileId = "survivor", mode = Mode.VPN),
            afterReboot.lastSession(),
        )
    }

    @Test
    fun `a blank profile id round-trips without becoming a present-but-empty pointer`() {
        store.recordSession(profileId = "", mode = Mode.VPN)

        val pointer = store.lastSession()
        assertEquals("", pointer?.profileId)
        assertEquals(Mode.VPN, pointer?.mode)
    }
}
