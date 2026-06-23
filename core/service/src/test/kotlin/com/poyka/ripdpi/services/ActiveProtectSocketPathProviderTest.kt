package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveProtectSocketPathProviderTest {
    @Test
    fun `current is null until a path is advertised`() {
        assertNull(ActiveProtectSocketPathProvider().current())
    }

    @Test
    fun `set advertises the path and clear removes it`() {
        val provider = ActiveProtectSocketPathProvider()
        val path = "/data/user/0/com.poyka.ripdpi/files/protect_path"

        provider.set(path)
        assertEquals(path, provider.current())

        provider.clear()
        assertNull(provider.current())
    }
}
