package com.poyka.ripdpi.services

import app.cash.turbine.test
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStateManagerTest {

    @After
    fun tearDown() {
        AppStateManager.setStatus(AppStatus.Halted, Mode.VPN)
    }

    @Test
    fun `initial status is Halted VPN`() {
        val (status, mode) = AppStateManager.status.value
        assertEquals(AppStatus.Halted, status)
        assertEquals(Mode.VPN, mode)
    }

    @Test
    fun `setStatus updates status flow`() {
        AppStateManager.setStatus(AppStatus.Running, Mode.Proxy)
        val (status, mode) = AppStateManager.status.value
        assertEquals(AppStatus.Running, status)
        assertEquals(Mode.Proxy, mode)
    }

    @Test
    fun `emitFailed sends event`() = runTest {
        AppStateManager.events.test {
            AppStateManager.emitFailed(Sender.VPN)
            val event = awaitItem()
            assertTrue(event is ServiceEvent.Failed)
            assertEquals(Sender.VPN, (event as ServiceEvent.Failed).sender)
        }
    }

    @Test
    fun `emitFailed proxy sends correct sender`() = runTest {
        AppStateManager.events.test {
            AppStateManager.emitFailed(Sender.Proxy)
            val event = awaitItem() as ServiceEvent.Failed
            assertEquals(Sender.Proxy, event.sender)
        }
    }
}
