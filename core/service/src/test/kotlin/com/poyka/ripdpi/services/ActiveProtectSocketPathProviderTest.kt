package com.poyka.ripdpi.services

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
}
