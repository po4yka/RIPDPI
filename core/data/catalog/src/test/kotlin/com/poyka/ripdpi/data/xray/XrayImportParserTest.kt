package com.poyka.ripdpi.data.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Tracks the completed `render-validated-xray-client-configs` task (see git history).
 */
class XrayImportParserTest {
    private val parser = XrayImportParser()

    /** Fixed upstream version for the supported REALITY+XHTTP configuration. */
    private val stableTag = "v1.260206.0"

    private val uuid = "550e8400-e29b-41d4-a716-446655440000"
    private val pbk = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    private fun accepted(result: XrayImportParser.Result): XrayImportParser.Result.Accepted {
        assertTrue("expected Accepted but was $result", result is XrayImportParser.Result.Accepted)
        return result as XrayImportParser.Result.Accepted
    }

    private fun rejected(result: XrayImportParser.Result): XrayImportParser.Result.Rejected {
        assertTrue("expected Rejected but was $result", result is XrayImportParser.Result.Rejected)
        return result as XrayImportParser.Result.Rejected
    }

    @Test
    fun `raw JSON rejects invalid endpoint identity and REALITY values`() {
        val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val link = "vless://$uuid@edge.example.com:443?security=reality&pbk=$key&sni=fixture.test&sid=ab12"
        val valid = accepted(parser.parse(link, stableTag)).config.toString()
        val invalid =
            listOf(
                valid.replace("\"address\":\"edge.example.com\"", "\"address\":\"\""),
                valid.replace("\"port\":443", "\"port\":0"),
                valid.replace("\"port\":443", "\"port\":65536"),
                valid.replace(uuid, ""),
                valid.replace(uuid, "x".repeat(31)),
                valid.replace(key, ""),
                valid.replace(key, "invalid-key"),
                valid.replace("ab12", "xyz"),
            )
        val acceptedIndices =
            invalid.indices.filter {
                parser.parse(invalid[it], stableTag) is XrayImportParser.Result.Accepted
            }
        assertEquals("Invalid field variants must be rejected", emptyList<Int>(), acceptedIndices)
    }

    @Test
    fun `URI parameters incompatible with selected transport or security are rejected`() {
        val tcp = "vless://$uuid@edge.example.com:443?security=tls&sni=fixture.test"
        val reality = "vless://$uuid@edge.example.com:443?security=reality&pbk=$pbk&sni=fixture.test"
        val links =
            listOf(
                tcp + "&path=%2Fignored",
                tcp + "&host=ignored.test",
                tcp + "&mode=stream-one",
                tcp + "&pbk=$pbk",
                tcp + "&sid=ab12",
                reality + "&allowInsecure=false",
            )
        val acceptedIndices =
            links.indices.filter {
                parser.parse(
                    links[it],
                    stableTag,
                ) is XrayImportParser.Result.Accepted
            }
        assertEquals("Supplied options must not disappear", emptyList<Int>(), acceptedIndices)
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
    fun `fixed upstream supports REALITY XHTTP in both release and module tag formats`() {
        val link = "vless://$uuid@edge.example.com:443?type=xhttp&security=reality&pbk=$pbk&sni=decoy.example.com"
        for (tag in listOf("v26.3.27", "1.260327.0", "v26.2.6", "v1.260206.0")) {
            assertEquals("", accepted(parser.parse(link, tag)).profile!!.outbound.flow)
        }
    }

    @Test
    fun `XHTTP retains empty flow instead of injecting incompatible Vision`() {
        val link = "vless://$uuid@edge.example.com:443?type=xhttp&security=tls&sni=cdn.example.com&flow="
        assertEquals("", accepted(parser.parse(link, stableTag)).profile!!.outbound.flow)
    }

    @Test
    fun `encoded XHTTP path is decoded exactly once`() {
        val link = "vless://$uuid@edge.example.com:443?type=xhttp&security=tls&sni=cdn.example.com&path=%2F%252F"
        assertEquals(
            "/%2F",
            accepted(parser.parse(link, stableTag))
                .profile!!
                .outbound.xhttp!!
                .path,
        )
    }

    @Test
    fun `ambiguous or malformed URI query cannot silently discard options`() {
        val link = "vless://$uuid@edge.example.com:443?type=xhttp&security=tls&sni=cdn.example.com"
        for (suffix in listOf(
            "&allowInsecure=true&allowInsecure=false",
            "&allowInsecure",
            "&path=%GG",
            "&sni=other.example",
        )) {
            rejected(parser.parse(link + suffix, stableTag))
        }
    }

    @Test
    fun explicitEmptyXhttpPathRemainsEmpty() {
        val link =
            "vless://$uuid@edge.example.com:8443" +
                "?type=xhttp&security=tls&sni=cdn.example.com&path=&mode=auto#x"

        val profile = accepted(parser.parse(link, stableTag)).profile!!

        assertEquals(XrayProfile.Network.XHTTP, profile.outbound.network)
        assertEquals("", profile.outbound.xhttp?.path)
    }

    @Test
    fun `raw JSON imports a durable typed profile without changing its config`() {
        val link = "vless://$uuid@edge.example.com:443?security=reality&pbk=$pbk&sni=decoy.example.com"
        val original = accepted(parser.parse(link, stableTag, "Saved profile"))

        val imported = accepted(parser.parse(original.config.toString(), stableTag, "Saved profile"))

        assertEquals(original.profile, imported.profile)
        assertEquals(original.config, imported.config)
        assertEquals(original.capabilities, imported.capabilities)
    }

    @Test
    fun `unsafe URI options are rejected instead of silently rewritten`() {
        val link = "vless://$uuid@edge.example.com:443?type=xhttp&security=tls&sni=cdn.example.com&allowInsecure=1"
        assertEquals(XrayImportParser.Reason.FAILED_SAFETY_CHECK, rejected(parser.parse(link, stableTag)).reason)
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

    @Test
    fun invalidXhttpTesterErrorNeverEchoesSuppliedVlessLinkValues() {
        val xhttpPath = "/secret-carrier-path"
        val xhttpHost = "carrier.secret.example"
        val echoedLink =
            "vless://$uuid@edge.secret.example:443" +
                "?type=xhttp&security=reality&pbk=$pbk&sni=decoy.secret.example" +
                "&sid=deadbeef&path=$xhttpPath&host=$xhttpHost#x"
        val parser =
            XrayImportParser(
                renderer =
                    XrayConfigRenderer(
                        XrayConfigTester {
                            XrayConfigTester.TestResult.Invalid("xray-core parse failed near $echoedLink")
                        },
                    ),
            )

        val result = rejected(parser.parse(echoedLink, stableTag))

        assertEquals(XrayImportParser.Reason.FAILED_SAFETY_CHECK, result.reason)
        listOf(uuid, pbk, "edge.secret.example", "decoy.secret.example", "deadbeef", xhttpPath, xhttpHost).forEach {
            assertFalse("message leaked supplied value $it", result.message.contains(it))
        }
        assertTrue(result.message.contains(XrayProfileRedactor.REDACTED))
    }
}
