package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashTrojanOptionsAdmissionTest {
    @Test
    fun `unsupported Trojan handshake constraints reject instead of disappearing`() {
        listOf(
            "fingerprint: " + "ab".repeat(32),
            "alpn: [h2]",
            "ss-opts: {enabled: true, method: aes-128-gcm, password: synthetic}",
            "client-fingerprint: chrome",
            "dialer-proxy: upstream",
            "reality-opts: {}",
        ).forEach { options ->
            val result = ClashSubscriptionParser.parse(trojan(options), "group")
            assertTrue(options, result.profiles.isEmpty())
            assertEquals(options, 1, result.errors.size)
        }
    }

    @Test
    fun `ordinary verified Trojan still imports with its server name`() {
        val result = ClashSubscriptionParser.parse(trojan("skip-cert-verify: false"), "group")
        assertTrue(result.errors.isEmpty())
        assertEquals("front.example", (result.profiles.single() as ProxyProfile.Trojan).serverName)
    }

    private fun trojan(options: String): String =
        """proxies:
          |  - name: synthetic
          |    type: trojan
          |    server: relay.example
          |    port: 443
          |    password: synthetic
          |    sni: front.example
          |    $options
        """.trimMargin()
}
