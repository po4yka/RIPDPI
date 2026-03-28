package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppStatusTest {
    @Test
    fun `Mode fromString parses proxy`() {
        assertEquals(Mode.Proxy, Mode.fromString("proxy"))
    }

    @Test
    fun `Mode fromString parses vpn`() {
        assertEquals(Mode.VPN, Mode.fromString("vpn"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Mode fromString rejects unknown mode`() {
        Mode.fromString("invalid")
    }

    @Test
    fun `Mode preferenceValue matches fromString round trip`() {
        Mode.entries.forEach { mode ->
            assertEquals(mode, Mode.fromString(mode.preferenceValue))
        }
    }

    @Test
    fun `AppStatus enum values exist`() {
        assertEquals(2, AppStatus.entries.size)
        assertEquals(AppStatus.Halted, AppStatus.valueOf("Halted"))
        assertEquals(AppStatus.Running, AppStatus.valueOf("Running"))
    }
}
