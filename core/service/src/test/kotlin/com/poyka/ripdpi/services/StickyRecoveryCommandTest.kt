package com.poyka.ripdpi.services

import android.net.VpnService
import com.poyka.ripdpi.data.DesiredRuntimeState
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.testPauseAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StickyRecoveryCommandTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `always-on start after a user stop starts the stored profile`() {
        val authority = testPauseAuthority().apply { reserveStop() }

        val command =
            createStickyRecoveryCommand(context, authority, ServiceIntentArbiter(authority), Mode.VPN, alwaysOn = true)

        assertNotNull(command)
        assertEquals(VpnService.SERVICE_INTERFACE, command?.action)
        assertNotNull(command.capturedRuntimeActivation(authority, Mode.VPN))
        val durable = authority.snapshotAuthority()
        assertEquals(DesiredRuntimeState.Running, durable.desired)
        assertEquals(Mode.VPN.preferenceValue, durable.desiredMode)
    }

    @Test
    fun `sticky restart after a user stop stays stopped`() {
        val authority = testPauseAuthority().apply { reserveStop() }

        val command = createStickyRecoveryCommand(context, authority, ServiceIntentArbiter(authority), Mode.VPN)

        assertNull(command)
        assertEquals(DesiredRuntimeState.Stopped, authority.snapshotAuthority().desired)
    }

    @Test
    fun `always-on start keeps an active pause`() {
        val authority = testPauseAuthority()
        authority.begin(Mode.VPN, 300_000, authority.snapshotAuthority())

        val command =
            createStickyRecoveryCommand(context, authority, ServiceIntentArbiter(authority), Mode.VPN, alwaysOn = true)

        assertNull(command)
        assertNotNull(authority.snapshot())
    }

    @Test
    fun `null action is an always-on start only while always-on is active`() {
        assertTrue(isAlwaysOnStart(VpnService.SERVICE_INTERFACE, alwaysOnActive = false))
        assertTrue(isAlwaysOnStart(null, alwaysOnActive = true))
        assertFalse(isAlwaysOnStart(null, alwaysOnActive = false))
    }
}
