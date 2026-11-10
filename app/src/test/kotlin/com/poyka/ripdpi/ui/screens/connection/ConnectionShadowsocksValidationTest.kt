package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.RelayKindShadowsocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `Shadowsocks UDP is kept only for SS2022 methods`() {
        val draft =
            ConnectionDraft(
                relayEnabled = true,
                relayKind = RelayKindShadowsocks,
                relayProfileId = "shadowsocks-udp",
                relayServer = "relay.example",
                relayServerPort = "443",
                relayShadowsocksMethod = "aes-256-gcm",
                relayShadowsocksPassword = "fixture-pass",
                relayUdpEnabled = true,
            )
        assertFalse(draft.supportsUdpRelay())
        assertNull(validateConfigDraft(draft)[ConnectionFieldRelayCredentials])
        assertFalse(draft.toRelayProfileRecord(draft.relayProfileId).udpEnabled)

        val ss2022 =
            draft.copy(
                relayShadowsocksMethod = "2022-blake3-aes-128-gcm",
                relayShadowsocksPassword = "AAECAwQFBgcICQoLDA0ODw==", // Public key fixture.
            )
        assertTrue(ss2022.supportsUdpRelay())
        assertNull(validateConfigDraft(ss2022)[ConnectionFieldRelayCredentials])
        assertTrue(ss2022.toRelayProfileRecord(ss2022.relayProfileId).udpEnabled)

        // AppSettings snapshots carry no credentials; re-applying one must keep UDP.
        val settingsOnly = draft.copy(relayShadowsocksMethod = "", relayShadowsocksPassword = "")
        assertTrue(
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .applyConfigDraft(settingsOnly)
                .build()
                .relayUdpEnabled,
        )
    }
}
