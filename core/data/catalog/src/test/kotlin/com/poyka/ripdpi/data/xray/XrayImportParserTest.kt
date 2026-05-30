package com.poyka.ripdpi.data.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural coverage for [XrayImportParser] — the fail-closed import flow.
 *
 * Asserts:
 * - A REALITY `vless://` link imports to an Accepted typed profile + capabilities.
 * - An XHTTP `vless://` link imports and carries the anti-DPI capability.
 * - A raw JSON config imports through the validate gate.
 * - Unsupported transports, missing fields, unknown schemes, and unsafe configs
 *   all fail CLOSED (Rejected).
 * - Rejection messages never leak the UUID / key / host from the input.
 *
 * Tracks `docs/tasks/issues/render-validated-xray-client-configs.md`.
 */
class XrayImportParserTest {
    private val parser = XrayImportParser()

    /** Pre-26.1.18 tag so REALITY+XHTTP is not version-gated in these tests. */
    private val stableTag = "v1.260206.0"

    private val uuid = "550e8400-e29b-41d4-a716-446655440000"
    private val pbk = "AbCdEf0123456789AbCdEf0123456789AbCdEf01234"

    private fun accepted(result: XrayImportParser.Result): XrayImportParser.Result.Accepted {
        assertTrue("expected Accepted but was $result", result is XrayImportParser.Result.Accepted)
        return result as XrayImportParser.Result.Accepted
    }

    private fun rejected(result: XrayImportParser.Result): XrayImportParser.Result.Rejected {
        assertTrue("expected Rejected but was $result", result is XrayImportParser.Result.Rejected)
        return result as XrayImportParser.Result.Rejected
    }

    @Test
    fun realityVlessLinkImportsToTypedProfile() {
        val link =
            "vless://$uuid@edge.example.com:443" +
                "?security=reality&pbk=$pbk&sni=www.cloudflare.com&sid=abcd&fp=chrome&flow=xtls-rprx-vision#node"
        val result = accepted(parser.parse(link, stableTag))

        val profile = assertNotNull("typed profile present", result.profile).let { result.profile!! }
        assertEquals("edge.example.com", profile.outbound.serverAddress)
        assertEquals(443, profile.outbound.serverPort)
        assertEquals(uuid, profile.outbound.uuid)
        assertEquals(XrayProfile.Security.REALITY, profile.outbound.security)
        assertEquals(pbk, profile.outbound.reality?.publicKey)
        assertEquals("www.cloudflare.com", profile.outbound.reality?.serverName)
        assertTrue(result.capabilities.contains(XrayCapability.VPN_PRIVACY))
        assertTrue(result.capabilities.contains(XrayCapability.ANTI_DPI))
    }

    @Test
    fun xhttpVlessLinkImportsAndAdvertisesAntiDpi() {
        val link =
            "vless://$uuid@edge.example.com:8443" +
                "?type=xhttp&security=tls&sni=cdn.example.com&path=%2Fhls&mode=auto#x"
        val result = accepted(parser.parse(link, stableTag))
        val profile = result.profile!!
        assertEquals(XrayProfile.Network.XHTTP, profile.outbound.network)
        assertEquals("/hls", profile.outbound.xhttp?.path)
        assertTrue(result.capabilities.contains(XrayCapability.ANTI_DPI))
    }

    @Test
    fun rawJsonConfigImportsThroughValidateGate() {
        // Minimal valid xray config: a single direct outbound with a flow'd vless user.
        val rawConfig =
            """
            {
              "outbounds": [
                {
                  "protocol": "vless",
                  "settings": {
                    "vnext": [
                      { "users": [ { "id": "$uuid", "flow": "xtls-rprx-vision" } ] }
                    ]
                  },
                  "streamSettings": { "network": "tcp", "security": "reality" }
                }
              ]
            }
            """.trimIndent()
        val result = accepted(parser.parse(rawConfig, stableTag))
        assertNull("raw-json import has no typed profile", result.profile)
        assertNotNull(result.config)
    }

    @Test
    fun unknownSchemeFailsClosed() {
        val result = rejected(parser.parse("ss://not-a-vless-link", stableTag))
        assertEquals(XrayImportParser.Reason.UNRECOGNISED_INPUT, result.reason)
    }

    @Test
    fun unsupportedTransportFailsClosed() {
        val link = "vless://$uuid@edge.example.com:443?type=grpc&security=reality&pbk=$pbk&sni=a.example.com#g"
        val result = rejected(parser.parse(link, stableTag))
        assertEquals(XrayImportParser.Reason.UNSUPPORTED_TRANSPORT, result.reason)
    }

    @Test
    fun missingRealityParamsFailsClosed() {
        val link = "vless://$uuid@edge.example.com:443?security=reality#noPbk"
        val result = rejected(parser.parse(link, stableTag))
        assertEquals(XrayImportParser.Reason.MISSING_REQUIRED_FIELD, result.reason)
    }

    @Test
    fun missingUuidFailsClosed() {
        val link = "vless://@edge.example.com:443?security=reality&pbk=$pbk&sni=a.example.com#x"
        val result = rejected(parser.parse(link, stableTag))
        assertEquals(XrayImportParser.Reason.MISSING_REQUIRED_FIELD, result.reason)
    }

    @Test
    fun allowInsecureRawConfigFailsSafetyCheck() {
        val rawConfig =
            """
            {
              "outbounds": [
                {
                  "protocol": "vless",
                  "settings": { "vnext": [ { "users": [ { "id": "$uuid", "flow": "xtls-rprx-vision" } ] } ] },
                  "streamSettings": { "security": "tls", "tlsSettings": { "allowInsecure": true } }
                }
              ]
            }
            """.trimIndent()
        val result = rejected(parser.parse(rawConfig, stableTag))
        assertEquals(XrayImportParser.Reason.FAILED_SAFETY_CHECK, result.reason)
        assertTrue(result.message.contains("certificate", ignoreCase = true))
    }

    @Test
    fun malformedJsonFailsClosed() {
        val result = rejected(parser.parse("{ not valid json", stableTag))
        assertEquals(XrayImportParser.Reason.UNRECOGNISED_INPUT, result.reason)
    }

    @Test
    fun rejectionMessageNeverLeaksSecrets() {
        // REALITY+XHTTP at a broken tag → FAILED_SAFETY_CHECK with a redacted message.
        val brokenTag = "v26.1.18"
        val link =
            "vless://$uuid@edge.example.com:443" +
                "?type=xhttp&security=reality&pbk=$pbk&sni=secret.example.com&path=%2Fp#x"
        val result = rejected(parser.parse(link, brokenTag))
        assertEquals(XrayImportParser.Reason.FAILED_SAFETY_CHECK, result.reason)
        assertFalse("message must not leak UUID", result.message.contains(uuid))
        assertFalse("message must not leak public key", result.message.contains(pbk))
        assertFalse("message must not leak server address", result.message.contains("edge.example.com"))
    }
}
