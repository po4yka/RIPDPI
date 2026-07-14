package com.poyka.ripdpi.services

import android.content.Intent
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.boot.BootSessionPointer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootResumeTest {
    private val vpnPointer = BootSessionPointer(profileId = "default", mode = Mode.VPN)

    @Test
    fun `skips when no session pointer was recorded`() {
        val decision =
            decideBootResume(
                action = Intent.ACTION_BOOT_COMPLETED,
                pointer = null,
                profileResumable = true,
                wasRunningAtUpdate = true,
            )
        assertTrue(decision is BootResumeDecision.Skip)
    }

    @Test
    fun `skips when the recorded profile is no longer resumable`() {
        val decision =
            decideBootResume(
                Intent.ACTION_BOOT_COMPLETED,
                pointer = vpnPointer,
                profileResumable = false,
                wasRunningAtUpdate = true,
            )
        assertTrue(decision is BootResumeDecision.Skip)
    }

    @Test
    fun `boot completed resumes the recorded mode`() {
        val decision =
            decideBootResume(
                action = Intent.ACTION_BOOT_COMPLETED,
                pointer = vpnPointer,
                profileResumable = true,
                wasRunningAtUpdate = false,
            )
        assertEquals(BootResumeDecision.Resume(Mode.VPN), decision)
    }

    @Test
    fun `locked boot completed is skipped until credential storage is available`() {
        val proxyPointer = BootSessionPointer(profileId = "p", mode = Mode.Proxy)
        val decision =
            decideBootResume(
                action = Intent.ACTION_LOCKED_BOOT_COMPLETED,
                pointer = proxyPointer,
                profileResumable = true,
                wasRunningAtUpdate = false,
            )
        assertTrue(decision is BootResumeDecision.Skip)
    }

    @Test
    fun `package replaced resumes only when the session was running at update`() {
        val decision =
            decideBootResume(
                action = Intent.ACTION_MY_PACKAGE_REPLACED,
                pointer = vpnPointer,
                profileResumable = true,
                wasRunningAtUpdate = true,
            )
        assertEquals(BootResumeDecision.Resume(Mode.VPN), decision)
    }

    @Test
    fun `package replaced is skipped when the session was not running at update`() {
        val decision =
            decideBootResume(
                action = Intent.ACTION_MY_PACKAGE_REPLACED,
                pointer = vpnPointer,
                profileResumable = true,
                wasRunningAtUpdate = false,
            )
        assertTrue(decision is BootResumeDecision.Skip)
    }

    @Test
    fun `unhandled action is skipped`() {
        val decision =
            decideBootResume(
                action = "android.intent.action.SCREEN_ON",
                pointer = vpnPointer,
                profileResumable = true,
                wasRunningAtUpdate = true,
            )
        assertTrue(decision is BootResumeDecision.Skip)
    }

    @Test
    fun `reconnecting is published on an accepted start from a halted store`() {
        assertEquals(
            Mode.VPN,
            reconnectingResumeMode(ServiceStartResult.Accepted(Mode.VPN), AppStatus.Halted),
        )
        assertEquals(
            Mode.Proxy,
            reconnectingResumeMode(ServiceStartResult.Accepted(Mode.Proxy), AppStatus.Halted),
        )
    }

    @Test
    fun `a rejected start never publishes reconnecting - no stuck state`() {
        assertNull(
            reconnectingResumeMode(
                ServiceStartResult.Rejected(Mode.VPN, ServiceStartRejectionReason.VpnConsentMissing),
                AppStatus.Halted,
            ),
        )
    }

    @Test
    fun `an already-active store is not demoted to reconnecting`() {
        // e.g. an automation-intercepted Accepted that dispatched no foreground service,
        // or a resume racing a still-running session.
        assertNull(reconnectingResumeMode(ServiceStartResult.Accepted(Mode.VPN), AppStatus.Running))
        assertNull(reconnectingResumeMode(ServiceStartResult.Accepted(Mode.VPN), AppStatus.Reconnecting))
    }

    @Test
    fun `default profile id is always resumable`() =
        runTest {
            val guard = DefaultBootResumeProfileGuard(FakeProxyGroupRepository(groups = emptyList()))
            assertTrue(guard.isResumable("default"))
            assertTrue(guard.isResumable(""))
        }

    @Test
    fun `non-default profile is not resumable when no proxy groups remain`() =
        runTest {
            val guard = DefaultBootResumeProfileGuard(FakeProxyGroupRepository(groups = emptyList()))
            assertFalse(guard.isResumable("some-subscription-profile"))
        }

    @Test
    fun `non-default profile is resumable while at least one proxy group exists`() =
        runTest {
            val guard =
                DefaultBootResumeProfileGuard(
                    FakeProxyGroupRepository(
                        groups =
                            listOf(
                                ProxyGroup(
                                    id = "g1",
                                    name = "Group",
                                    type = ProxyGroupType.SUBSCRIPTION,
                                    order = 0,
                                    isSelector = false,
                                ),
                            ),
                    ),
                )
            assertTrue(guard.isResumable("some-subscription-profile"))
        }

    private class FakeProxyGroupRepository(
        private val groups: List<ProxyGroup>,
    ) : ProxyGroupRepository {
        override suspend fun add(group: ProxyGroup) = Unit

        override suspend fun update(group: ProxyGroup) = Unit

        override suspend fun delete(id: String) = Unit

        override suspend fun list(): List<ProxyGroup> = groups

        override fun groups(): Flow<List<ProxyGroup>> = flowOf(groups)
    }
}
