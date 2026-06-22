package com.poyka.ripdpi.data

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [validateNativeRelayProfile] signals invalid field material by throwing; the
 * [validateNativeRelayProfileResult] wrapper must surface that as a failed
 * [Result] so UI callers can show an error instead of crashing the coroutine.
 */
class NativeRelayProfileValidationResultTest {
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
}
