package com.poyka.ripdpi.data.xray

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Secret-leak regression matrix for the Xray provider.
 *
 * Rendered Xray configs and any diagnostic text derived from them carry hard
 * secrets — VLESS UUIDs, REALITY public keys, server addresses/SNIs. These
 * MUST never reach a log line, telemetry export, or bug report. The redaction
 * lane locks that:
 *
 * 1. A redacted rendered config contains NONE of the profile's secret material.
 * 2. A redacted typed summary contains NONE of the secret material.
 * 3. Validation rejection messages never echo secret material (the renderer
 *    returns typed errors, never the secret-bearing config).
 *
 * Distinct synthetic secrets are used per field so a leak of any single field
 * is caught by substring search. No real endpoints appear anywhere.
 */
class XrayRedactionRegressionTest {
    private val secretUuid = "fa11ed00-1111-2222-3333-444455556666"
    private val secretRealityKey = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8"
    private val secretServer = "secret-edge.internal.example"
    private val secretSni = "decoy.internal.example"
    private val secretShortId = "deadbeef99"

    /** Every distinct secret that must NOT survive redaction. */
    private val secrets =
        listOf(secretUuid, secretRealityKey, secretServer, secretSni)

    private fun realityProfile(): XrayProfile =
        XrayProfile(
            name = "redaction-probe",
            outbound =
                XrayProfile.Outbound(
                    serverAddress = secretServer,
                    serverPort = 8443,
                    uuid = secretUuid,
                    flow = "xtls-rprx-vision",
                    security = XrayProfile.Security.REALITY,
                    network = XrayProfile.Network.TCP,
                    reality =
                        XrayProfile.Reality(
                            publicKey = secretRealityKey,
                            serverName = secretSni,
                            shortId = secretShortId,
                            fingerprint = "chrome",
                        ),
                ),
        )

    private fun renderedJson(): String {
        val result = XrayConfigRenderer(XrayConfigTester.NoOp).render(realityProfile(), "v1.260206.0")
        result as XrayConfigRenderer.Result.Success
        return Json.encodeToString(
            kotlinx.serialization.json.JsonObject
                .serializer(),
            result.config,
        )
    }

    @Test
    fun `rendered config contains the secrets before redaction`() {
        // Sanity guard: if the renderer stopped embedding these, the redaction
        // assertions below would pass vacuously. Prove the secrets are present.
        val json = renderedJson()
        assertTrue("uuid present pre-redaction", json.contains(secretUuid))
        assertTrue("reality key present pre-redaction", json.contains(secretRealityKey))
        assertTrue("server present pre-redaction", json.contains(secretServer))
        assertTrue("sni present pre-redaction", json.contains(secretSni))
    }

    @Test
    fun `redacting a rendered config removes every secret`() {
        val redacted = XrayProfileRedactor.redactText(renderedJson())
        for (secret in secrets) {
            assertFalse("secret leaked after redactText: $secret", redacted.contains(secret))
        }
        // The placeholder is present, proving redaction actually fired.
        assertTrue(redacted.contains(XrayProfileRedactor.REDACTED))
    }

    @Test
    fun `typed profile summary leaks no secret material`() {
        val summary = XrayProfileRedactor.redact(realityProfile())
        for (secret in secrets) {
            assertFalse("secret leaked in summary: $secret", summary.contains(secret))
        }
        // shortId is not in the explicit secrets list but must also not appear.
        assertFalse("shortId leaked in summary", summary.contains(secretShortId))
        // Non-secret metadata (flow / security / network) is allowed to remain.
        assertTrue(summary.contains("xtls-rprx-vision"))
        assertTrue(summary.contains("reality"))
    }

    @Test
    fun `validation rejection never echoes the secret-bearing config`() {
        // Force a rejection by clearing the required VLESS flow; the renderer
        // must surface typed errors with NO secret in their messages.
        val noFlow =
            realityProfile().let { p ->
                p.copy(outbound = p.outbound.copy(flow = ""))
            }
        val result = XrayConfigRenderer(XrayConfigTester.NoOp).render(noFlow, "v1.260206.0")
        result as XrayConfigRenderer.Result.Rejected

        assertTrue("a validation error was raised", result.validationErrors.isNotEmpty())
        val joined = result.validationErrors.joinToString("\n") { "${it.code} ${it.path} ${it.message}" }
        for (secret in secrets) {
            assertFalse("secret leaked in validation error: $secret", joined.contains(secret))
        }
        assertFalse("shortId leaked in validation error", joined.contains(secretShortId))
    }

    @Test
    fun `tester rejection message carries no secret`() {
        // A tester that rejects must produce a secret-safe message; the renderer
        // does not append the config to it.
        val rejectingTester =
            XrayConfigTester { XrayConfigTester.TestResult.Invalid("xray-core rejected transport") }
        val result = XrayConfigRenderer(rejectingTester).render(realityProfile(), "v1.260206.0")
        result as XrayConfigRenderer.Result.Rejected

        val msg = result.testError.orEmpty()
        assertEquals("xray-core rejected transport", msg)
        for (secret in secrets) {
            assertFalse("secret leaked in tester error: $secret", msg.contains(secret))
        }
    }

    @Test
    fun `free-form VLESS query fragments are redacted`() {
        val carrierPath = "/carrier-secret-path"
        val carrierHost = "carrier.internal.example"
        val blob =
            "invalid params pbk=$secretRealityKey sid=$secretShortId sni=$secretSni " +
                "host=$carrierHost path=$carrierPath id=$secretUuid"

        val redacted = XrayProfileRedactor.redactText(blob)

        listOf(secretRealityKey, secretShortId, secretSni, carrierHost, carrierPath, secretUuid).forEach {
            assertFalse("query value leaked after redactText: $it", redacted.contains(it))
        }
        assertTrue(redacted.contains(XrayProfileRedactor.REDACTED))
    }
}
