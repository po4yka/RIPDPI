package com.poyka.ripdpi.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD coverage for the WS-tunnel profile-import validator.
 *
 * The native ws-tunnel runtime already refuses `fake_sni` at connect
 * time when `allow_insecure_sni` is unset (commit 41d69a14 added the
 * `PermissionDenied` gate). This validator catches the same
 * misconfiguration at *import* time — when a profile is added from
 * a catalog, file, or QR code — so the user sees the error
 * immediately rather than at first connect.
 *
 * Tracks the service-layer side of
 * `docs/tasks/issues/gate-fake-sni-cert-bypass-behind-allow-insecure-flag-with-telemetry.md`.
 */
class WsProfileImportValidatorTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(raw: String): JsonObject = json.decodeFromString(JsonObject.serializer(), raw)

    @Test
    fun `profile without fake_sni passes validation`() {
        val profile = parse("""{"protect_path":"/dev/null","resolved_addr":"1.2.3.4:443"}""")
        val errors = WsProfileImportValidator.validate(profile)
        assertEquals(emptyList<WsProfileImportValidator.ValidationError>(), errors)
    }

    @Test
    fun `profile with fake_sni and allow_insecure_sni true passes validation`() {
        val profile = parse("""{"fake_sni":"yandex.ru","allow_insecure_sni":true}""")
        val errors = WsProfileImportValidator.validate(profile)
        assertEquals(emptyList<WsProfileImportValidator.ValidationError>(), errors)
    }

    @Test
    fun `profile with fake_sni but missing allow_insecure_sni is rejected`() {
        val profile = parse("""{"fake_sni":"yandex.ru"}""")
        val errors = WsProfileImportValidator.validate(profile)
        assertTrue(
            "must reject fake_sni without allow_insecure_sni; got $errors",
            errors.any { it.code == WsProfileImportValidator.ErrorCode.FAKE_SNI_WITHOUT_ACK },
        )
    }

    @Test
    fun `profile with fake_sni and allow_insecure_sni false is rejected`() {
        val profile = parse("""{"fake_sni":"yandex.ru","allow_insecure_sni":false}""")
        val errors = WsProfileImportValidator.validate(profile)
        assertTrue(
            "must reject fake_sni when allow_insecure_sni is explicitly false; got $errors",
            errors.any { it.code == WsProfileImportValidator.ErrorCode.FAKE_SNI_WITHOUT_ACK },
        )
    }

    @Test
    fun `empty profile passes validation`() {
        val profile = parse("""{}""")
        val errors = WsProfileImportValidator.validate(profile)
        assertEquals(emptyList<WsProfileImportValidator.ValidationError>(), errors)
    }
}
