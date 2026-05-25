package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.diagnostics.export.redactDiagnosticsLogcat
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fuzz-style redaction tests for [DiagnosticsArchiveRedactor].
 *
 * Each test constructs a model with non-default sensitive field values and asserts
 * that no verbatim original value reaches the encoded JSON output.
 * This guards the privacy boundary required by POY-14 / F-01..F-03.
 */
class DiagnosticsArchiveRedactorTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
            encodeDefaults = true
            explicitNulls = false
        }

    private val redactor = DiagnosticsArchiveRedactor(json)

    @Test
    fun `redactor strips public ip asn dns servers local addresses ssid bssid and gateway from network snapshot`() {
        val sensitiveModel =
            NetworkSnapshotModel(
                transport = "wifi",
                capabilities = listOf("validated"),
                dnsServers = listOf("203.0.113.53", "198.51.100.1"),
                privateDnsMode = "strict",
                mtu = 1500,
                localAddresses = listOf("192.0.2.10", "192.0.2.11"),
                publicIp = "203.0.113.99",
                publicAsn = "AS64500",
                captivePortalDetected = false,
                networkValidated = true,
                wifiDetails =
                    WifiNetworkDetails(
                        ssid = "PrivateHomeWifi",
                        bssid = "AA:BB:CC:DD:EE:FF",
                        band = "5 GHz",
                        wifiStandard = "802.11ax",
                        gateway = "192.0.2.1",
                        dhcpServer = "192.0.2.2",
                        ipAddress = "192.0.2.42",
                        subnetMask = "255.255.255.0",
                    ),
                capturedAt = 1L,
            )
        val entity =
            NetworkSnapshotEntity(
                id = "snap-sensitive",
                sessionId = "session-redact",
                snapshotKind = "post_scan",
                payloadJson = json.encodeToString(NetworkSnapshotModel.serializer(), sensitiveModel),
                capturedAt = 1L,
            )

        val redactedEntity = redactor.redact(entity)
        val encoded = redactedEntity.payloadJson

        assertFalse("public IP must not appear verbatim", encoded.contains("203.0.113.99"))
        assertFalse("public ASN must not appear verbatim", encoded.contains("AS64500"))
        assertFalse("dns server IP must not appear verbatim", encoded.contains("203.0.113.53"))
        assertFalse("secondary dns server IP must not appear verbatim", encoded.contains("198.51.100.1"))
        assertFalse("local address must not appear verbatim", encoded.contains("192.0.2.10"))
        assertFalse("secondary local address must not appear verbatim", encoded.contains("192.0.2.11"))
        assertFalse("SSID must not appear verbatim", encoded.contains("PrivateHomeWifi"))
        assertFalse("BSSID must not appear verbatim", encoded.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse("gateway must not appear verbatim", encoded.contains("192.0.2.1"))
        assertFalse("dhcpServer must not appear verbatim", encoded.contains("192.0.2.2"))
        assertFalse("ipAddress must not appear verbatim", encoded.contains("192.0.2.42"))
        assertFalse("subnetMask must not appear verbatim", encoded.contains("255.255.255.0"))
        assertTrue("redacted marker must be present", encoded.contains("redacted"))
    }

    @Test
    fun `redactor strips credentials and free-text sensitive tokens from native event message`() {
        val sensitiveEvent =
            NativeSessionEventEntity(
                id = "event-sensitive",
                sessionId = "session-redact",
                source = "proxy",
                level = "warn",
                message =
                    "upstream connect failed: Authorization: Basic c2VjcmV0 " +
                        "url=https://admin:hunter2@private.example.test/api?token=abc123 " +
                        "ssid=\"CoffeeShop\" bssid=11:22:33:44:55:66",
                createdAt = 2L,
                runtimeId = "rt-token=supersecret",
                policySignature = "sig-password=relay-key-xyz",
            )

        val redacted = redactor.redact(sensitiveEvent)
        val encoded = json.encodeToString(NativeSessionEventEntity.serializer(), redacted)

        assertFalse("Basic credential must not appear verbatim", encoded.contains("c2VjcmV0"))
        assertFalse("user:pass credential must not appear verbatim", encoded.contains("admin:hunter2"))
        assertFalse("token value must not appear verbatim", encoded.contains("abc123"))
        assertFalse("SSID value must not appear verbatim", encoded.contains("CoffeeShop"))
        assertFalse("BSSID must not appear verbatim", encoded.contains("11:22:33:44:55:66"))
        assertFalse("runtime token value must not appear verbatim", encoded.contains("supersecret"))
        assertFalse("policy password value must not appear verbatim", encoded.contains("relay-key-xyz"))
        assertTrue("redacted marker must be present", encoded.contains("redacted"))
    }

    @Test
    fun `redactor leaves unknown ssid unchanged`() {
        val modelWithUnknownSsid =
            NetworkSnapshotModel(
                transport = "wifi",
                capabilities = emptyList(),
                dnsServers = emptyList(),
                privateDnsMode = "off",
                mtu = null,
                localAddresses = emptyList(),
                publicIp = null,
                publicAsn = null,
                captivePortalDetected = false,
                networkValidated = false,
                wifiDetails =
                    WifiNetworkDetails(
                        ssid = "unknown",
                        bssid = "unknown",
                        band = "2.4 GHz",
                        wifiStandard = "802.11n",
                    ),
                capturedAt = 3L,
            )
        val entity =
            NetworkSnapshotEntity(
                id = "snap-unknown-ssid",
                sessionId = "session-redact-unknown",
                snapshotKind = "post_scan",
                payloadJson = json.encodeToString(NetworkSnapshotModel.serializer(), modelWithUnknownSsid),
                capturedAt = 3L,
            )

        val redactedEntity = redactor.redact(entity)
        val encoded = redactedEntity.payloadJson

        assertTrue("unknown ssid sentinel must be preserved", encoded.contains("\"unknown\""))
    }

    @Test
    fun `standalone log redactor uses diagnostics archive logcat policy`() {
        val raw =
            "Authorization: Bearer secret-token " +
                "https://admin:hunter2@private.example.test/api?token=abc123 " +
                "host=private.example.test addr=203.0.113.10 ssid=\"CoffeeShop\" bssid=11:22:33:44:55:66"

        val redacted = DiagnosticsLogRedactor().redactLogcat(raw)

        assertEquals(redactDiagnosticsLogcat(raw), redacted)
        assertFalse("Bearer token must not appear verbatim", redacted.contains("secret-token"))
        assertFalse("URL credential must not appear verbatim", redacted.contains("admin:hunter2"))
        assertFalse("query token must not appear verbatim", redacted.contains("abc123"))
        assertFalse("endpoint host must not appear verbatim", redacted.contains("private.example.test"))
        assertFalse("endpoint address must not appear verbatim", redacted.contains("203.0.113.10"))
        assertFalse("SSID value must not appear verbatim", redacted.contains("CoffeeShop"))
        assertFalse("BSSID must not appear verbatim", redacted.contains("11:22:33:44:55:66"))
        assertTrue("redacted marker must be present", redacted.contains("redacted"))
    }
}
