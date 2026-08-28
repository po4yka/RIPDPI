package com.poyka.ripdpi.data.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [XrayProfileRedactor]: every secret and endpoint identifier is
 * scrubbed from both the typed-profile summary and the free-form text path.
 *
 * Tracks the completed `render-validated-xray-client-configs` task (see git history).
 */
class XrayProfileRedactorTest {
    private val uuid = "550e8400-e29b-41d4-a716-446655440000"
    private val publicKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val privateKey = "PRIV_KEY_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
    private val serverAddress = "edge.secret-host.example.com"

    private fun secretProfile(): XrayProfile =
        XrayProfile(
            name = "my-server",
            outbound =
                XrayProfile.Outbound(
                    serverAddress = serverAddress,
                    serverPort = 443,
                    uuid = uuid,
                    flow = "xtls-rprx-vision",
                    security = XrayProfile.Security.REALITY,
                    network = XrayProfile.Network.TCP,
                    reality =
                        XrayProfile.Reality(
                            publicKey = publicKey,
                            serverName = "www.cloudflare.com",
                            shortId = "0123abcd",
                            privateKey = privateKey,
                        ),
                ),
        )

    @Test
    fun `redact summary removes uuid key and server address`() {
        val summary = XrayProfileRedactor.redact(secretProfile())

        assertFalse("uuid leaked: $summary", summary.contains(uuid))
        assertFalse("publicKey leaked: $summary", summary.contains(publicKey))
        assertFalse("privateKey leaked: $summary", summary.contains(privateKey))
        assertFalse("server address leaked: $summary", summary.contains(serverAddress))
        assertFalse("reality SNI leaked: $summary", summary.contains("www.cloudflare.com"))
        // Non-secret fields stay readable.
        assertTrue(summary.contains("my-server"))
        assertTrue(summary.contains("security=reality"))
        assertTrue(summary.contains("network=tcp"))
    }

    @Test
    fun `redactText scrubs secrets and addresses from a rendered config`() {
        val rendered =
            when (val r = XrayConfigRenderer().render(secretProfile(), "v1.260206.0")) {
                is XrayConfigRenderer.Result.Success -> {
                    Json.encodeToString(JsonObject.serializer(), r.config)
                }

                is XrayConfigRenderer.Result.Rejected -> {
                    error("profile should render: $r")
                }
            }

        // Sanity: the raw config does contain the secrets before redaction.
        assertTrue(rendered.contains(uuid))
        assertTrue(rendered.contains(publicKey))
        assertTrue(rendered.contains(serverAddress))

        val scrubbed = XrayProfileRedactor.redactText(rendered)

        assertFalse("uuid leaked: $scrubbed", scrubbed.contains(uuid))
        assertFalse("publicKey leaked: $scrubbed", scrubbed.contains(publicKey))
        assertFalse("server address leaked: $scrubbed", scrubbed.contains(serverAddress))
        assertFalse("reality SNI leaked: $scrubbed", scrubbed.contains("www.cloudflare.com"))
        // Structure is preserved — keys remain, values are placeholders.
        assertTrue(scrubbed.contains("\"id\":\"${XrayProfileRedactor.REDACTED}\""))
        assertTrue(scrubbed.contains(XrayProfileRedactor.REDACTED))
    }

    @Test
    fun `redactText catches bare uuid and password and private key in free-form blob`() {
        val blob =
            """
            error connecting user $uuid via "password":"hunter2" with "privateKey":"$privateKey"
            """.trimIndent()
        val scrubbed = XrayProfileRedactor.redactText(blob)

        assertFalse(scrubbed.contains(uuid))
        assertFalse(scrubbed.contains("hunter2"))
        assertFalse(scrubbed.contains(privateKey))
    }

    @Test
    fun `redactText leaves non-secret text untouched`() {
        val text = "started xray provider on 127.0.0.1:10808 with flow xtls-rprx-vision"
        assertEquals(text, XrayProfileRedactor.redactText(text))
    }
}
