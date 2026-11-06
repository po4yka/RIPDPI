package com.poyka.ripdpi.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [validateNativeRelayProfile] signals invalid field material by throwing; the
 * [validateNativeRelayProfileResult] wrapper must surface that as a failed
 * [Result] so UI callers can show an error instead of crashing the coroutine.
 */
class NativeRelayProfileValidationResultTest {
    @Test
    fun `shadowsocks admission accepts supported methods and AES identity keys`() {
        val key16 = "AAECAwQFBgcICQoLDA0ODw=="
        val key32 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        for ((method, password) in listOf(
            "aes-128-gcm" to "credential",
            "aes-256-gcm" to "credential",
            "chacha20-ietf-poly1305" to "credential",
            "2022-blake3-aes-128-gcm" to key16,
            "2022-blake3-aes-256-gcm" to key32,
            "2022-blake3-chacha20-poly1305" to key32,
            "2022-blake3-aes-128-gcm" to "$key16:$key16",
            "2022-blake3-aes-256-gcm" to "$key32:$key32:$key32",
            "2022-blake3-aes-128-gcm" to key16.dropLast(1),
            "2022-blake3-aes-128-gcm" to key16.trimEnd('='),
            "2022-blake3-aes-256-gcm" to key32.trimEnd('='),
            "2022-blake3-chacha20-poly1305" to key32.trimEnd('='),
        )) {
            assertTrue(method, validateNativeRelayProfileResult(shadowsocksProfile(method, password)).isSuccess)
        }
    }

    @Test
    fun `shadowsocks admission rejects invalid methods keys and endpoints`() {
        val key16 = "AAECAwQFBgcICQoLDA0ODw=="
        val key32 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        for ((method, password) in listOf(
            "none" to "credential",
            "aes-128-cfb" to "credential",
            "aes-128-gcm" to " ",
            "2022-blake3-aes-128-gcm" to key32,
            "2022-blake3-aes-256-gcm" to key16,
            "2022-blake3-aes-128-gcm" to "$key16:$key32",
            "2022-blake3-aes-128-gcm" to "$key32:$key16",
            "2022-blake3-aes-128-gcm" to "$key16::$key16",
            "2022-blake3-chacha20-poly1305" to "$key32:$key32",
            "2022-blake3-aes-128-gcm" to " $key16",
            "2022-blake3-aes-128-gcm" to "$key16=",
            "2022-blake3-aes-128-gcm" to "AAECAwQFBgcICQoLDA0ODx==",
            "2022-blake3-aes-128-gcm" to "_____________________w==",
        )) {
            assertTrue(method, validateNativeRelayProfileResult(shadowsocksProfile(method, password)).isFailure)
        }
        val valid = shadowsocksProfile("aes-128-gcm", "credential")
        assertTrue(validateNativeRelayProfileResult(valid.copy(server = "")).isFailure)
        assertTrue(validateNativeRelayProfileResult(valid.copy(serverPort = 0)).isFailure)
        assertTrue(validateNativeRelayProfileResult(valid.copy(serverPort = 65536)).isFailure)
    }

    private fun shadowsocksProfile(
        method: String,
        password: String,
    ) = ProxyProfile.Shadowsocks(
        id = "shadowsocks",
        displayName = "Shadowsocks",
        groupId = "group",
        server = "example.com",
        serverPort = 443,
        method = method,
        password = password,
    )

    @Test
    fun `admission rejects unsupported flows and TLS automatic XHTTP without changing profiles`() {
        val reality =
            ProxyProfile.VlessReality(
                id = "reality",
                displayName = "Reality",
                groupId = "group",
                server = "example.com",
                serverPort = 443,
                uuid = "11111111-2222-3333-4444-555555555555",
                realityPublicKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                realityShortId = "",
                serverName = "example.com",
                flow = "unsupported-flow",
            )
        val tls =
            ProxyProfile.Vless(
                id = "tls",
                displayName = "TLS",
                groupId = "group",
                server = "example.com",
                serverPort = 443,
                uuid = reality.uuid,
                serverName = "example.com",
                flow = "",
                xhttpPath = "/stream",
                xhttpMode = "auto",
            )

        assertTrue(validateNativeRelayProfileResult(reality).isFailure)
        assertTrue(
            validateNativeRelayProfileResult(
                reality.copy(flow = RelayVlessFlowVision, xhttpPath = "/stream"),
            ).isFailure,
        )
        assertTrue(validateNativeRelayProfileResult(tls).isFailure)
        assertTrue(validateNativeRelayProfileResult(tls.copy(xhttpMode = RelayXhttpModeStreamOne)).isSuccess)
        assertTrue(
            validateNativeRelayProfileResult(
                tls.copy(flow = RelayVlessFlowVision, xhttpMode = RelayXhttpModeStreamOne),
            ).isFailure,
        )
        org.junit.Assert.assertEquals("unsupported-flow", reality.flow)
        org.junit.Assert.assertEquals("auto", tls.xhttpMode)
    }

    @Test
    fun `result is failure for invalid vless reality fields instead of throwing`() {
        val invalid =
            ProxyProfile.VlessReality(
                id = "id",
                displayName = "bad",
                groupId = "",
                server = "example.com",
                serverPort = 443,
                uuid = "not-a-uuid",
                realityPublicKey = "",
                realityShortId = "",
                serverName = "",
            )

        assertTrue(
            "invalid reality fields must surface as a failed Result, not an exception",
            validateNativeRelayProfileResult(invalid).isFailure,
        )
    }

    @Test
    fun `result is success for valid vless reality`() {
        val valid =
            ProxyProfile.VlessReality(
                id = "id",
                displayName = "ok",
                groupId = "",
                server = "example.com",
                serverPort = 443,
                uuid = "11111111-2222-3333-4444-555555555555",
                // base64 of 32 bytes (valid X25519 key length).
                realityPublicKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                realityShortId = "",
                serverName = "cdn.example",
            )

        val result = validateNativeRelayProfileResult(valid)

        assertTrue(result.isSuccess)
        assertTrue("a native relay kind validates to true", result.getOrThrow())
    }

    @Test
    fun `pasted whitespace is rejected around the vless uuid and accepted around reality fields the engine trims`() {
        val uuid = "11111111-2222-3333-4444-555555555555"
        val encoder = java.util.Base64.getEncoder()
        val publicKey = encoder.encodeToString(ByteArray(32) { it.toByte() })

        assertTrue(isValidVlessUuid(uuid))
        assertFalse(isValidVlessUuid("$uuid\n"))
        assertTrue(isValidRealityPublicKey(publicKey))
        assertTrue(isValidRealityPublicKey(" $publicKey\n"))
        assertFalse(isValidRealityPublicKey(" "))
        assertTrue(isValidRealityShortId("ab12"))
        assertTrue(isValidRealityShortId("ab12 "))
        assertFalse(isValidRealityShortId("ab 12"))
    }
}
