package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.AmneziaWgSubscriptionProfile
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSkipReason
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the RIPDPI-extended sing-box bundle: a standard sing-box JSON
 * config with an extra top-level `ripdpi` object carrying AmneziaWG device-VPN
 * profiles and Hysteria2 salamander obfuscation extras.
 *
 * Run with: `./gradlew :core:data:testDebugUnitTest --tests
 * "com.poyka.ripdpi.data.SingBoxRipdpiExtensionParserTest"`
 */
class SingBoxRipdpiExtensionParserTest {
    private val groupId = "sub-ripdpi-ext"

    private fun success(result: SingBoxParseResult): SingBoxParseResult.Success {
        assertTrue("expected Success but got $result", result is SingBoxParseResult.Success)
        return result as SingBoxParseResult.Success
    }

    // -------------------------------------------------------------------------
    // AmneziaWG profiles
    // -------------------------------------------------------------------------

    @Test
    fun `ripdpi amneziawg produces awg subscription profile with correct params`() {
        val json =
            """
            {
              "outbounds": [
                { "type": "hysteria2", "tag": "hy2-node", "server": "hy2.example.com",
                  "server_port": 8443, "password": "hy2-secret" }
              ],
              "ripdpi": {
                "schema_version": 2,
                "amneziawg": [
                  {
                    "tag": "p2-awg-moscow",
                    "address": ["10.66.66.2/32", "fd77::2/128"],
                    "dns": ["1.1.1.1"],
                    "mtu": 1420,
                    "jc": 4, "jmin": 40, "jmax": 70,
                    "s1": 50, "s2": 100,
                    "wire_profile": "awg-3.1-ranges-v1", "h1": "1", "h2": "2", "h3": "3", "h4": "4",
                    "private_key_placeholder": true,
                    "peer": {
                      "public_key": "test-pub-key-base64==",
                      "preshared_key": "test-psk-base64==",
                      "endpoint": "10.0.0.1:51820",
                      "allowed_ips": ["0.0.0.0/0", "::/0"],
                      "persistent_keepalive": 25
                    }
                  }
                ]
              }
            }
            """.trimIndent()

        val result = success(SingBoxSubscriptionParser.parse(json, groupId))

        // Standard sing-box outbounds still imported.
        assertEquals(1, result.profiles.size)
        assertTrue(result.profiles[0] is ProxyProfile.Hysteria2)

        // AWG profile produced.
        assertEquals(1, result.amneziaWgProfiles.size)
        val awg: AmneziaWgSubscriptionProfile = result.amneziaWgProfiles[0]

        assertEquals("p2-awg-moscow", awg.displayName)
        assertEquals(groupId, awg.groupId)
        assertEquals("10.0.0.1", awg.server)
        assertEquals(51820, awg.serverPort)

        // private_key_placeholder == true -> empty string.
        assertEquals("", awg.interfacePrivateKey)

        assertEquals(listOf("10.66.66.2/32", "fd77::2/128"), awg.interfaceAddress)
        assertEquals(listOf("1.1.1.1"), awg.dns)
        assertEquals(1420, awg.mtu)

        assertEquals("test-pub-key-base64==", awg.peerPublicKey)
        assertEquals("test-psk-base64==", awg.peerPresharedKey)
        assertEquals(listOf("0.0.0.0/0", "::/0"), awg.allowedIps)
        assertEquals(25, awg.persistentKeepalive)

        // AmneziaWG obfuscation params.
        val params = awg.awg
        assertEquals(4, params.jc)
        assertEquals(40, params.jmin)
        assertEquals(70, params.jmax)
        assertEquals(50, params.s1)
        assertEquals(100, params.s2)
        assertEquals("1", params.h1)
        assertEquals("2", params.h2)
        assertEquals("3", params.h3)
        assertEquals("4", params.h4)
        assertNull(params.i1)
    }

    @Test
    fun `ripdpi amneziawg with optional i1 to i5 programs carries them onto params`() {
        val json =
            """
            {
              "outbounds": [],
              "ripdpi": {
                "schema_version": 2,
                "amneziawg": [
                  {
                    "tag": "p2-awg-spb",
                    "address": ["10.66.66.3/32", "fd77::2/128"],
                    "dns": ["8.8.8.8"],
                    "mtu": 1280,
                    "jc": 3, "jmin": 20, "jmax": 50,
                    "s1": 10, "s2": 20,
                    "wire_profile": "awg-3.1-ranges-v1", "h1": "11", "h2": "22", "h3": "33", "h4": "44",
                    "i1": "<b 0xdeadbeef>", "i3": "<b 0xcafebabe>",
                    "private_key_placeholder": true,
                    "peer": {
                      "public_key": "spb-pub-key==",
                      "endpoint": "10.0.0.2:51820",
                      "allowed_ips": ["0.0.0.0/0"]
                    }
                  }
                ]
              }
            }
            """.trimIndent()

        val result = success(SingBoxSubscriptionParser.parse(json, groupId))
        assertEquals(1, result.amneziaWgProfiles.size)
        val params = result.amneziaWgProfiles[0].awg

        assertEquals("<b 0xdeadbeef>", params.i1)
        assertNull(params.i2)
        assertEquals("<b 0xcafebabe>", params.i3)
        assertNull(params.i4)
        assertNull(params.i5)
    }

    @Test
    fun `ripdpi amneziawg missing public_key skips that entry`() {
        val json =
            """
            {
              "outbounds": [],
              "ripdpi": {
                "schema_version": 2,
                "amneziawg": [
                  {
                    "tag": "broken",
                    "address": ["10.66.66.4/32", "fd77::2/128"],"mtu":1330,
                    "peer": {
                      "endpoint": "10.0.0.3:51820"
                    }
                  }
                ]
              }
            }
            """.trimIndent()

        val result = success(SingBoxSubscriptionParser.parse(json, groupId))
        assertEquals(0, result.amneziaWgProfiles.size)
    }

    @Test
    fun `ripdpi amneziawg with non-zero S3 or S4 skips that entry`() {
        val json =
            """
            {
              "outbounds": [],
              "ripdpi": {
                "schema_version": 2,
                "amneziawg": [
                  {
                    "tag": "arm64-unsafe",
                    "wire_profile": "awg-3.1-ranges-v1",
                    "jc": 0, "jmin": 0, "jmax": 0, "s1": 0, "s2": 0,
                    "h1": "1", "h2": "2", "h3": "3", "h4": "4",
                    "address": ["10.66.66.2/32", "fd77::2/128"],
                    "mtu": 1330,
                    "s3": 1,
                    "s4": 0,
                    "private_key_placeholder": true,
                    "peer": {
                      "public_key": "pub==",
                      "endpoint": "10.0.0.3:51820"
                    }
                  }
                ]
              }
            }
            """.trimIndent()

        val result = success(SingBoxSubscriptionParser.parse(json, groupId))

        assertTrue(result.amneziaWgProfiles.isEmpty())
        assertEquals(SingBoxSkipReason.UNSUPPORTED_OBFUSCATION, result.skipped.single().reason)
        val safe = success(SingBoxSubscriptionParser.parse(json.replace("\"s3\": 1", "\"s3\": 0"), groupId))
        assertEquals(1, safe.amneziaWgProfiles.size)
    }

    // -------------------------------------------------------------------------
    // Hysteria2 extras — salamander obfs
    // -------------------------------------------------------------------------

    @Test
    fun `ripdpi hysteria_extras carries salamander password onto matching hysteria2 profile`() {
        val json =
            """
            {
              "outbounds": [
                { "type": "hysteria2", "tag": "hy2-main", "server": "hy2.example.com",
                  "server_port": 8443, "password": "hy2-secret" }
              ],
              "ripdpi": {
                "schema_version": 2,
                "hysteria_extras": {
                  "hy2-main": {
                    "obfs": { "type": "salamander", "password": "my-salamander-key" },
                    "insecure": false,
                    "port_hopping": { "ports": "20000-40000", "interval": "30s" }
                  }
                }
              }
            }
            """.trimIndent()

        val result = success(SingBoxSubscriptionParser.parse(json, groupId))
        assertEquals(1, result.profiles.size)

        val hy2 = result.profiles[0] as? ProxyProfile.Hysteria2
        assertNotNull("expected Hysteria2 profile", hy2)
        hy2!!

        assertEquals("hy2-secret", hy2.password)
        assertEquals("my-salamander-key", hy2.obfsPassword)
        assertEquals(false, hy2.insecure)
        assertEquals("20000-40000", hy2.portHopping?.ports)
        assertEquals(30000L, hy2.portHopping?.intervalMillis)
    }

    @Test
    fun `ripdpi hysteria_extras with non-salamander obfs type leaves obfsPassword null`() {
        val json =
            """
            {
              "outbounds": [
                { "type": "hysteria2", "tag": "hy2-node", "server": "hy2.example.com",
                  "server_port": 8443, "password": "hy2-pass" }
              ],
              "ripdpi": {
                "schema_version": 2,
                "hysteria_extras": {
                  "hy2-node": {
                    "obfs": { "type": "unknown-future-type", "password": "irrelevant" }
                  }
                }
              }
            }
            """.trimIndent()

        val result = success(SingBoxSubscriptionParser.parse(json, groupId))
        val hy2 = result.profiles[0] as ProxyProfile.Hysteria2

        assertNull(hy2.obfsPassword)
    }

    @Test
    fun `ripdpi hysteria_extras unmatched tag leaves other profiles unchanged`() {
        val json =
            """
            {
              "outbounds": [
                { "type": "hysteria2", "tag": "hy2-node", "server": "hy2.example.com",
                  "server_port": 8443, "password": "hy2-pass" }
              ],
              "ripdpi": {
                "schema_version": 2,
                "hysteria_extras": {
                  "hy2-other": {
                    "obfs": { "type": "salamander", "password": "key" }
                  }
                }
              }
            }
            """.trimIndent()

        val result = success(SingBoxSubscriptionParser.parse(json, groupId))
        val hy2 = result.profiles[0] as ProxyProfile.Hysteria2

        assertNull(hy2.obfsPassword)
    }

    // -------------------------------------------------------------------------
    // Plain sing-box (no ripdpi block) is unaffected
    // -------------------------------------------------------------------------

    @Test
    fun `plain sing-box config with no ripdpi key produces empty awgProfiles`() {
        val json =
            """
            {
              "outbounds": [
                { "type": "hysteria2", "tag": "hy2-plain", "server": "hy2.example.com",
                  "server_port": 8443, "password": "plain-secret" },
                { "type": "trojan", "tag": "trojan-node", "server": "trojan.example.com",
                  "server_port": 443, "password": "trojan-secret" }
              ]
            }
            """.trimIndent()

        val result = success(SingBoxSubscriptionParser.parse(json, groupId))

        assertEquals(2, result.profiles.size)
        assertTrue(result.amneziaWgProfiles.isEmpty())

        val hy2 = result.profiles[0] as ProxyProfile.Hysteria2
        assertNull(hy2.obfsPassword)
        assertNull(hy2.insecure)
        assertNull(hy2.portHopping)
    }

    // -------------------------------------------------------------------------
    // Unknown / future schema_version is gracefully ignored
    // -------------------------------------------------------------------------

    @Test
    fun `unknown and legacy bundle versions are recognized refusals`() {
        val json =
            """
            {
              "outbounds": [
                { "type": "trojan", "tag": "trojan-node", "server": "trojan.example.com",
                  "server_port": 443, "password": "trojan-secret" }
              ],
              "ripdpi": {
                "schema_version": 99,
                "amneziawg": [
                  {
                    "tag": "should-be-ignored",
                    "address": ["10.66.66.2/32", "fd77::2/128"],
                    "mtu": 1330,
                    "peer": { "public_key": "key==", "endpoint": "1.2.3.4:51820" }
                  }
                ],
                "hysteria_extras": {
                  "trojan-node": {
                    "obfs": { "type": "salamander", "password": "ignored" }
                  }
                }
              }
            }
            """.trimIndent()

        for (version in listOf(1, 3, 99)) {
            val result =
                SingBoxSubscriptionParser.parse(
                    json.replace("\"schema_version\": 99", "\"schema_version\": $version"),
                    groupId,
                )
            assertTrue(result is SingBoxParseResult.Error)
            assertTrue((result as SingBoxParseResult.Error).recognizedBundle)
        }
    }

    // -------------------------------------------------------------------------
    // Versioned contract fields (schema_version 2)
    // -------------------------------------------------------------------------

    @Test
    fun `ripdpi amneziawg cohort_fingerprint is carried and recomputes from params`() {
        val json =
            """
            {
              "outbounds": [],
              "ripdpi": {
                "schema_version": 2,
                "amneziawg": [
                  {
                    "tag": "p2-awg-x",
                    "address": ["10.66.66.2/32", "fd77::2/128"],
                    "mtu": 1420,
                    "jc": 4, "jmin": 40, "jmax": 70, "s1": 50, "s2": 100,
                    "wire_profile": "awg-3.1-ranges-v1", "h1": "1", "h2": "2", "h3": "3", "h4": "4",
                    "cohort_fingerprint": "sha256:49637303755f4b53700284c5fa62ff23497fb356f2345ff023f0cc3b13635f0b",
                    "private_key_placeholder": true,
                    "peer": { "public_key": "pub==", "endpoint": "10.0.0.1:51820" }
                  }
                ]
              }
            }
            """.trimIndent()

        val result = success(SingBoxSubscriptionParser.parse(json, groupId))
        val awg = result.amneziaWgProfiles.single()
        assertEquals(
            "sha256:49637303755f4b53700284c5fa62ff23497fb356f2345ff023f0cc3b13635f0b",
            awg.cohortFingerprint,
        )
        // The recomputed fingerprint matches the server-stamped one byte-for-byte.
        assertEquals(awg.cohortFingerprint, awg.awg.cohortFingerprint())
    }

    @Test
    fun `ripdpi hysteria_extras carries salamander_upstream_tag onto the matching profile`() {
        val json =
            """
            {
              "outbounds": [
                { "type": "hysteria2", "tag": "hy2-main", "server": "hy2.example.com",
                  "server_port": 8443, "password": "hy2-secret" }
              ],
              "ripdpi": {
                "schema_version": 2,
                "hysteria_extras": {
                  "hy2-main": {
                    "insecure": false,
                    "obfs": { "type": "salamander", "password": "key" },
                    "salamander_upstream_tag": "v2.9.0"
                  }
                }
              }
            }
            """.trimIndent()

        val result = success(SingBoxSubscriptionParser.parse(json, groupId))
        val hy2 = result.profiles[0] as ProxyProfile.Hysteria2
        assertEquals("v2.9.0", hy2.salamanderUpstreamTag)
    }

    @Test
    fun `ripdpi topology is parsed into the result`() {
        val json =
            """
            {
              "outbounds": [],
              "ripdpi": {
                "schema_version": 2,
                "topology": { "split_hop_egress": true, "hysteria_realm": "realm-a" }
              }
            }
            """.trimIndent()

        val result = success(SingBoxSubscriptionParser.parse(json, groupId))
        assertNotNull(result.topology)
        assertEquals(true, result.topology!!.splitHopEgress)
        assertEquals("realm-a", result.topology!!.hysteriaRealm)
    }

    @Test
    fun `ripdpi RFC3339 expiry is normalized to epoch milliseconds`() {
        val json =
            """
            {
              "outbounds": [],
              "ripdpi": {
                "schema_version": 2,
                "expires": "2026-12-31T23:59:59Z"
              }
            }
            """.trimIndent()

        val result = success(SingBoxSubscriptionParser.parse(json, groupId))
        assertEquals(1_798_761_599_000L, result.tokenExpiresAtEpochMillis)
    }

    @Test
    fun `ripdpi date-only expiry is midnight UTC`() {
        val json =
            """
            {
              "outbounds": [],
              "ripdpi": {
                "schema_version": 2,
                "expires": "2026-12-31"
              }
            }
            """.trimIndent()

        val result = success(SingBoxSubscriptionParser.parse(json, groupId))
        assertEquals(1_798_675_200_000L, result.tokenExpiresAtEpochMillis)
    }

    @Test
    fun `malformed ripdpi expiry does not reject otherwise valid bundle`() {
        val json =
            """
            {
              "outbounds": [],
              "ripdpi": {
                "schema_version": 2,
                "expires": "not-a-date"
              }
            }
            """.trimIndent()

        val result = success(SingBoxSubscriptionParser.parse(json, groupId))
        assertNull(result.tokenExpiresAtEpochMillis)
    }
}
