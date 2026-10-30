package com.poyka.ripdpi.services

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NaiveUpstreamConfigTest {
    private val values =
        NaiveUpstreamConfigValues(
            proxyHost = "naive.test",
            proxyPort = 8443,
            proxyUsername = "alice",
            proxyPassword = "pw", // Fixture credential.
            localUsername = "lu",
            localPassword = "lp",
            localHost = "127.0.0.1",
            localPort = 1080,
            extraHeaders = "",
            mappedAddress = null,
        )

    @Test
    fun `HTTP3 selects the quic proxy scheme and concurrency above one is written`() {
        val quic = JSONObject(naiveUpstreamConfigJson(values.copy(quic = true, concurrency = 3)))

        assertEquals("quic://alice:pw@naive.test:8443", quic.getString("proxy"))
        assertEquals(3, quic.getInt("insecure-concurrency"))

        val default = JSONObject(naiveUpstreamConfigJson(values))
        assertEquals("https://alice:pw@naive.test:8443", default.getString("proxy"))
        assertFalse(default.has("insecure-concurrency"))
    }
}
