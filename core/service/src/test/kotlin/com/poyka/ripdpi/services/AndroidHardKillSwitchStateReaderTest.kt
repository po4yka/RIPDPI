package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.stopAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidHardKillSwitchStateReaderTest {
    @Test
    fun `enabled lockdown blocks every service stop source`() {
        val snapshot =
            AndroidHardKillSwitchStateReader.fromPlatformFlags(
                alwaysOn = true,
                lockdown = true,
            )

        assertFalse(snapshot.allowsServiceStop(stopAction))
        assertFalse(snapshot.allowsServiceStop(notificationStopAction))
    }

    @Test
    fun `unknown state hides notification stop but preserves explicit recovery`() {
        val snapshot = AndroidHardKillSwitchStateReader.unknown()

        assertTrue(snapshot.allowsServiceStop(stopAction))
        assertFalse(snapshot.allowsServiceStop(notificationStopAction))
    }

    @Test
    fun `known disabled lockdown allows every service stop source`() {
        val snapshot =
            AndroidHardKillSwitchStateReader.fromPlatformFlags(
                alwaysOn = false,
                lockdown = false,
            )

        assertTrue(snapshot.allowsServiceStop(stopAction))
        assertTrue(snapshot.allowsServiceStop(notificationStopAction))
    }

    @Test
    fun `enabled only when always-on and lockdown are both true`() {
        val snapshot =
            AndroidHardKillSwitchStateReader.fromPlatformFlags(
                alwaysOn = true,
                lockdown = true,
                nowMillis = 123L,
            )

        assertEquals(AndroidHardKillSwitchStatus.ENABLED, snapshot.status)
        assertTrue(snapshot.alwaysOn == true)
        assertTrue(snapshot.lockdown == true)
        assertEquals(123L, snapshot.updatedAtMillis)
        assertTrue(snapshot.isEnabled)
    }

    @Test
    fun `not enabled when always-on is missing`() {
        val snapshot =
            AndroidHardKillSwitchStateReader.fromPlatformFlags(
                alwaysOn = false,
                lockdown = true,
                nowMillis = 124L,
            )

        assertEquals(AndroidHardKillSwitchStatus.NOT_ENABLED, snapshot.status)
        assertFalse(snapshot.isEnabled)
    }

    @Test
    fun `not enabled when lockdown is missing`() {
        val snapshot =
            AndroidHardKillSwitchStateReader.fromPlatformFlags(
                alwaysOn = true,
                lockdown = false,
                nowMillis = 125L,
            )

        assertEquals(AndroidHardKillSwitchStatus.NOT_ENABLED, snapshot.status)
        assertFalse(snapshot.isEnabled)
    }

    @Test
    fun `unknown preserves nullable platform flags`() {
        val snapshot = AndroidHardKillSwitchStateReader.unknown(nowMillis = 126L)

        assertEquals(AndroidHardKillSwitchStatus.UNKNOWN, snapshot.status)
        assertNull(snapshot.alwaysOn)
        assertNull(snapshot.lockdown)
        assertEquals(126L, snapshot.updatedAtMillis)
    }
}
