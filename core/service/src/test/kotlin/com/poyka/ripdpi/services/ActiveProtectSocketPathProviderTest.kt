package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RelaySocketProtection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveProtectSocketPathProviderTest {
    @Test
    fun `old session withdrawal preserves replacement protection`() {
        val provider = ActiveProtectSocketPathProvider()
        val oldLease = provider.set("old-session") { true }
        val oldController = requireNotNull(provider.captureDirectProtection())
        val oldCleanup = { provider.clear(oldLease) }
        val newLease = provider.set("new-session") { true }
        val replacement = requireNotNull(provider.captureDirectProtection())

        oldCleanup()

        assertEquals("new-session", provider.current())
        assertFalse(oldController.protect(17))
        assertTrue(replacement.protect(17))
        oldCleanup()
        assertTrue(replacement.protect(17))
        provider.clear(newLease)
        assertNull(provider.current())
        assertFalse(replacement.protect(17))
    }

    @Test
    fun `current is null until a path is advertised`() {
        assertNull(ActiveProtectSocketPathProvider().current())
    }

    @Test
    fun `set advertises the path and clear removes it`() {
        val provider = ActiveProtectSocketPathProvider()
        val path = "/data/user/0/com.poyka.ripdpi/files/protect_path"

        val lease = provider.set(path) { true }
        assertEquals(path, provider.current())

        provider.clear(lease)
        assertNull(provider.current())
    }

    @Test
    fun `inactive probe reservation rejects VPN setup before routes and rolls back`() {
        val provider = ActiveProtectSocketPathProvider()
        val probe = provider.captureWarpProbe()
        assertEquals(RelaySocketProtection.Inactive, probe.socketProtection)
        assertTrue(probe.controller.protect(17))
        var serverRunning = false
        var nativeRegistered = false
        var routesCreated = false
        val failure =
            runCatching {
                establishProtectPath(
                    startProtectSocketServer = { serverRunning = true },
                    advertiseProtectPath = { provider.set("vpn") { true } },
                    registerNativeProtect = { nativeRegistered = true },
                    rollbackProtection = { serverRunning = false },
                )
                routesCreated = true
            }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(serverRunning)
        assertFalse(nativeRegistered)
        assertFalse(routesCreated)
        probe.close()
        probe.close()
        assertFalse(probe.controller.protect(17))
        val owner = provider.set("vpn") { true }
        provider.clear(owner)
    }

    @Test
    fun `captured required probe rejects replacement and retained cleanup owners`() {
        val provider = ActiveProtectSocketPathProvider()
        provider.set("old") { true }
        val old = provider.captureWarpProbe()
        assertEquals(RelaySocketProtection.VpnRequired, old.socketProtection)
        val owner = provider.set("new") { true }
        assertFalse(old.controller.protect(17))
        val current = provider.captureWarpProbe()
        assertTrue(current.controller.protect(17))
        provider.closeProbeAdmission(owner)
        assertEquals("new", provider.current())
        assertFalse(current.controller.protect(17))
        provider.captureWarpProbe().use { pending ->
            assertEquals(RelaySocketProtection.VpnRequired, pending.socketProtection)
            assertFalse(pending.controller.protect(17))
        }
        provider.clear(owner)
        assertFalse(current.controller.protect(17))
        old.close()
        current.close()
    }
}
