package com.poyka.ripdpi.services

import com.poyka.ripdpi.service.session.vpn.vpnProtectSocketPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VpnProtectSocketPathTest {
    @Test
    fun `each session allocates a distinct short filesystem endpoint`() {
        val directory = File("/data/user/0/com.poyka.ripdpi/files")
        val first = File(vpnProtectSocketPath(directory))
        val second = File(vpnProtectSocketPath(directory))
        assertEquals(directory, first.parentFile)
        assertEquals(directory, second.parentFile)
        assertNotEquals(first, second)
        assertTrue(first.name.matches(Regex("vp-[0-9a-f]{32}")))
        assertTrue(first.path.toByteArray().size < 108)
    }
}
