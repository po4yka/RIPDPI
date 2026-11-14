package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.DefaultFakeSni
import org.junit.Assert.assertEquals
import org.junit.Test

class FakePacketNormalizationTest {
    @Test
    fun `invalid or blank fake sni falls back to default`() {
        for (value in listOf("", "1.2.3.4", "bad host/ü", "host:443", "a".repeat(300) + ".com")) {
            val config = normalizeFakePacketConfig(RipDpiFakePacketConfig(fakeSni = value))

            assertEquals(value, DefaultFakeSni, config.fakeSni)
        }
    }

    @Test
    fun `valid fake sni is normalized`() {
        val config = normalizeFakePacketConfig(RipDpiFakePacketConfig(fakeSni = "WWW.Example.org."))

        assertEquals("www.example.org", config.fakeSni)
    }
}
