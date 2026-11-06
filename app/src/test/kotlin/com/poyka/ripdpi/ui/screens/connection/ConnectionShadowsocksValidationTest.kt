package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.RelayKindShadowsocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionShadowsocksValidationTest {
    @Test
    fun `Shadowsocks 2022 requires standard base64 key of cipher length`() {
        val draft =
            ConnectionDraft(
                relayEnabled = true,
                relayKind = RelayKindShadowsocks,
                relayProfileId = "shadowsocks-2022",
                relayServer = "relay.example",
                relayServerPort = "443",
                relayShadowsocksMethod = "2022-blake3-aes-128-gcm",
                relayShadowsocksPassword = "AAECAwQFBgcICQoLDA0ODw==", // Public key fixture.
            )
        assertNull(validateConfigDraft(draft)[ConnectionFieldRelayCredentials])
        assertEquals(
            "invalid",
            validateConfigDraft(draft.copy(relayShadowsocksPassword = "foo"))[ConnectionFieldRelayCredentials],
        )
        assertEquals(
            "invalid",
            validateConfigDraft(
                draft.copy(
                    relayShadowsocksMethod = "2022-blake3-aes-256-gcm",
                    relayShadowsocksPassword = "AAECAwQFBgcICQoLDA0ODw==", // Public key fixture.
                ),
            )[ConnectionFieldRelayCredentials],
        )
    }

    @Test
    fun `Shadowsocks AES identity chains validate every key`() {
        for ((method, key) in listOf(
            "2022-blake3-aes-128-gcm" to "AAECAwQFBgcICQoLDA0ODw==",
            "2022-blake3-aes-256-gcm" to "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        )) {
            val draft =
                ConnectionDraft(
                    relayEnabled = true,
                    relayKind = RelayKindShadowsocks,
                    relayProfileId = "shadowsocks-identity",
                    relayServer = "relay.example",
                    relayServerPort = "443",
                    relayShadowsocksMethod = method,
                    relayShadowsocksPassword = "$key:$key:$key", // Public key fixture.
                )
            assertNull(validateConfigDraft(draft)[ConnectionFieldRelayCredentials])
            for (invalid in listOf("Zm9v:$key", "$key:Zm9v", "$key:Zm9v:$key", "$key:*", ":$key", "$key:")) {
                assertEquals(
                    "invalid",
                    validateConfigDraft(
                        draft.copy(relayShadowsocksPassword = invalid),
                    )[ConnectionFieldRelayCredentials],
                )
            }
        }
    }

    @Test
    fun `Shadowsocks ChaCha accepts one key and rejects identity chains`() {
        val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        val draft =
            ConnectionDraft(
                relayEnabled = true,
                relayKind = RelayKindShadowsocks,
                relayProfileId = "shadowsocks-chacha",
                relayServer = "relay.example",
                relayServerPort = "443",
                relayShadowsocksMethod = "2022-blake3-chacha20-poly1305",
                relayShadowsocksPassword = key,
            )
        assertNull(validateConfigDraft(draft)[ConnectionFieldRelayCredentials])
        assertEquals(
            "invalid",
            validateConfigDraft(
                draft.copy(relayShadowsocksPassword = "$key:$key"), // Public key fixture.
            )[ConnectionFieldRelayCredentials],
        )
    }
}
