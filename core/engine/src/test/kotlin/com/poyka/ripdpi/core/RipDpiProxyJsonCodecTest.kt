package com.poyka.ripdpi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RipDpiProxyJsonCodecTest {
    @Test
    fun `ui preferences round trip direct path capabilities through runtime context`() {
        val preferences =
            RipDpiProxyUIPreferences(
                runtimeContext =
                    RipDpiRuntimeContext(
                        directPathCapabilities =
                            listOf(
                                RipDpiDirectPathCapability(
                                    authority = "Example.org:443",
                                    quicUsable = false,
                                    udpUsable = true,
                                    fallbackRequired = true,
                                    repeatedHandshakeFailureClass = " tcp_reset ",
                                    updatedAt = 321L,
                                ),
                            ),
                    ),
            )

        val decoded = decodeRipDpiProxyUiPreferences(preferences.toNativeConfigJson())
        val capability = decoded?.runtimeContext?.directPathCapabilities?.singleOrNull()

        assertNotNull(capability)
        assertEquals("example.org:443", capability?.authority)
        assertEquals(false, capability?.quicUsable)
        assertEquals(true, capability?.udpUsable)
        assertEquals(true, capability?.fallbackRequired)
        assertEquals("tcp_reset", capability?.repeatedHandshakeFailureClass)
        assertEquals(321L, capability?.updatedAt)
    }
}
