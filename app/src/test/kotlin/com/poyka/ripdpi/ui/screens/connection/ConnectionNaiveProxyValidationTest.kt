package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import org.junit.Assert.assertEquals
import org.junit.Test

private fun validNaiveProxyDraft(relayNaivePath: String): ConnectionDraft =
    AppSettingsSerializer.defaultValue.toConfigDraft().copy(
        relayEnabled = true,
        relayKind = RelayKindNaiveProxy,
        relayServer = "relay.example",
        relayServerPort = "443",
        relayServerName = "relay.example",
        relayNaiveUsername = "naive-user",
        relayNaivePassword = "naive-" + "credential",
        relayNaivePath = relayNaivePath,
    )

class ConnectionNaiveProxyValidationTest {
    @Test
    fun `relay validation rejects naiveproxy missing credentials`() {
        val errors =
            validateConfigDraft(
                AppSettingsSerializer.defaultValue.toConfigDraft().copy(
                    relayEnabled = true,
                    relayKind = RelayKindNaiveProxy,
                    relayServer = "relay.example",
                    relayServerPort = "443",
                    relayServerName = "relay.example",
                ),
            )

        assertEquals("required", errors[ConnectionFieldRelayCredentials])
    }

    @Test
    fun `relay validation accepts naiveproxy username with empty password`() {
        val errors = validateConfigDraft(validNaiveProxyDraft(relayNaivePath = "").copy(relayNaivePassword = ""))
        val passwordOnly =
            validateConfigDraft(
                validNaiveProxyDraft(relayNaivePath = "").copy(relayNaiveUsername = "", relayNaivePassword = "x"),
            )

        assertEquals(null, errors[ConnectionFieldRelayCredentials])
        assertEquals("required", passwordOnly[ConnectionFieldRelayCredentials])
    }

    @Test
    fun `relay validation rejects invalid naiveproxy Basic credentials`() {
        val valid = validNaiveProxyDraft(relayNaivePath = "")
        val invalid =
            listOf(
                valid.copy(relayNaiveUsername = "private:user"),
                valid.copy(relayNaiveUsername = "private\u0000user"),
                valid.copy(relayNaivePassword = "secret\npass"), // Fixture credential.
                valid.copy(relayNaivePassword = "secret\u007fpass"), // Fixture credential.
            )
        for (draft in invalid) {
            assertEquals("invalid", validateConfigDraft(draft)[ConnectionFieldRelayCredentials])
        }
        val preserved =
            valid.copy(
                relayNaiveUsername = " алиса ",
                relayNaivePassword = " secret:пароль ", // Fixture credential.
            )
        assertEquals(null, validateConfigDraft(preserved)[ConnectionFieldRelayCredentials])
    }

    @Test
    fun `relay validation accepts only a sha256 pin for naiveproxy`() {
        fun errorFor(pin: String): String? =
            validateConfigDraft(validNaiveProxyDraft(relayNaivePath = "").copy(relayNaiveServerPinSha256 = pin))[
                ConnectionFieldRelayNaiveServerPin,
            ]

        assertEquals(null, errorFor(""))
        assertEquals(null, errorFor("AB:".repeat(31) + "AB"))
        assertEquals("invalid_pin", errorFor("abcd"))
        assertEquals("invalid_pin", errorFor("zz".repeat(32)))
    }

    @Test
    fun `relay validation mirrors helper rules for naiveproxy extra headers`() {
        fun errorFor(headers: String): String? =
            validateConfigDraft(validNaiveProxyDraft(relayNaivePath = "").copy(relayNaiveExtraHeaders = headers))[
                ConnectionFieldRelayNaiveExtraHeaders,
            ]

        assertEquals(null, errorFor(""))
        assertEquals(null, errorFor("X-Token: a\nX-Other: b"))
        assertEquals("invalid_headers", errorFor("no colon here"))
        assertEquals("invalid_headers", errorFor("bad name: v"))
        assertEquals("reserved_header", errorFor("Proxy-Authorization: Basic eA=="))
        assertEquals("invalid_headers", errorFor((1..17).joinToString("\n") { "x-$it: v" }))
    }

    @Test
    fun `relay validation accepts naiveproxy blank or absolute path`() {
        val blankPathErrors =
            validateConfigDraft(
                validNaiveProxyDraft(relayNaivePath = ""),
            )
        val absolutePathErrors =
            validateConfigDraft(
                validNaiveProxyDraft(relayNaivePath = "/proxy"),
            )

        assertEquals(null, blankPathErrors[ConnectionFieldRelayNaivePath])
        assertEquals(null, absolutePathErrors[ConnectionFieldRelayNaivePath])
        assertEquals(null, blankPathErrors[ConnectionFieldRelayCredentials])
        assertEquals(null, absolutePathErrors[ConnectionFieldRelayCredentials])
    }

    @Test
    fun `relay validation rejects naiveproxy relative path`() {
        val errors =
            validateConfigDraft(
                validNaiveProxyDraft(relayNaivePath = "proxy"),
            )

        assertEquals("absolute_path", errors[ConnectionFieldRelayNaivePath])
    }
}
