package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.diagnostics.export.redactDiagnosticsArchiveText
import com.poyka.ripdpi.diagnostics.export.redactDiagnosticsLogcat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fuzz-style redaction tests for [DiagnosticsArchiveRedactor].
 *
 * Each test constructs a model with non-default sensitive field values and asserts
 * that no verbatim original value reaches the encoded JSON output.
 * This guards the diagnostics archive privacy boundary.
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
    fun `redactor replaces undecodable payloads without retaining their content`() {
        val snapshot =
            NetworkSnapshotEntity(
                id = "snapshot-id",
                sessionId = "session-id",
                connectionSessionId = "connection-id",
                snapshotKind = "post_scan",
                payloadJson = "{\"secret\":\"snapshot-secret-token\",\"ssid\":\"PrivateNetwork\"}",
                capturedAt = 41L,
            )
        val context =
            DiagnosticContextEntity(
                id = "context-id",
                sessionId = "session-id",
                connectionSessionId = "connection-id",
                contextKind = "service_state",
                payloadJson = "{\"password\":\"context-secret-token\",\"endpoint\":\"private.example\"}",
                capturedAt = 42L,
            )

        val redactedSnapshot = redactor.redact(snapshot)
        val redactedContext = redactor.redact(context)
        val marker = "{\"redactionStatus\":\"payload_decode_failed\"}"

        assertEquals(snapshot.copy(payloadJson = marker), redactedSnapshot)
        assertEquals(context.copy(payloadJson = marker), redactedContext)
        assertFalse(redactedSnapshot.payloadJson.contains("snapshot-secret-token"))
        assertFalse(redactedSnapshot.payloadJson.contains("PrivateNetwork"))
        assertFalse(redactedContext.payloadJson.contains("context-secret-token"))
        assertFalse(redactedContext.payloadJson.contains("private.example"))
    }

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
    fun `redactor projects strict private dns and cellular identity to coarse metadata`() {
        val sensitiveModel =
            NetworkSnapshotModel(
                transport = "cellular",
                capabilities = listOf("validated"),
                dnsServers = emptyList(),
                privateDnsMode = "private-dns.customer.example",
                mtu = 1420,
                localAddresses = emptyList(),
                publicIp = null,
                publicAsn = null,
                captivePortalDetected = false,
                networkValidated = true,
                cellularDetails =
                    CellularNetworkDetails(
                        carrierName = "Sensitive Carrier",
                        simOperatorName = "Sensitive SIM",
                        networkOperatorName = "Sensitive Operator",
                        networkCountryIso = "ge",
                        simCountryIso = "ge",
                        operatorCode = "28201",
                        simOperatorCode = "28202",
                        dataNetworkType = "lte",
                        voiceNetworkType = "umts",
                        dataState = "connected",
                        serviceState = "in_service",
                        isNetworkRoaming = false,
                        carrierId = 1_234_567_890,
                        simCarrierId = 987_654_321,
                        signalLevel = 3,
                        signalDbm = -91,
                    ),
                capturedAt = 4L,
            )

        val encoded = json.encodeToString(NetworkSnapshotModel.serializer(), redactor.redact(sensitiveModel))
        val projected = json.parseToJsonElement(encoded).jsonObject
        val cellular = projected.getValue("cellularDetails").jsonObject

        assertEquals("strict", projected.getValue("privateDnsMode").jsonPrimitive.content)
        assertEquals("redacted", cellular.getValue("carrierName").jsonPrimitive.content)
        assertEquals("redacted", cellular.getValue("simOperatorName").jsonPrimitive.content)
        assertEquals("redacted", cellular.getValue("networkOperatorName").jsonPrimitive.content)
        assertEquals("redacted", cellular.getValue("operatorCode").jsonPrimitive.content)
        assertEquals("redacted", cellular.getValue("simOperatorCode").jsonPrimitive.content)
        assertFalse("carrierId must not be exported", cellular.containsKey("carrierId"))
        assertFalse("simCarrierId must not be exported", cellular.containsKey("simCarrierId"))
        assertEquals("ge", cellular.getValue("networkCountryIso").jsonPrimitive.content)
        assertEquals("lte", cellular.getValue("dataNetworkType").jsonPrimitive.content)
        assertEquals("3", cellular.getValue("signalLevel").jsonPrimitive.content)
        assertFalse(encoded.contains("private-dns.customer.example"))
        assertFalse(encoded.contains("Sensitive Carrier"))
        assertFalse(encoded.contains("28201"))
        assertFalse(encoded.contains("1234567890"))
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
    fun `redactor projects probe detail key value pairs by declared sensitive key`() {
        val entity =
            ProbeResultEntity(
                id = "probe-sensitive",
                sessionId = "session-redact",
                probeType = "custom",
                target = "opaque-internal-target",
                outcome = "failed",
                detailJson =
                    """[{"key":"host","value":"opaque-internal-host"},""" +
                        """{"key":"selectedDnscryptPublicKey","value":"opaque-public-key"}]""",
                createdAt = 3L,
            )

        val redacted = redactor.redact(entity)

        assertEquals("redacted", redacted.target)
        assertFalse(redacted.detailJson.contains("opaque-internal-host"))
        assertFalse(redacted.detailJson.contains("opaque-public-key"))
        assertTrue(redacted.detailJson.contains("redacted"))
    }

    @Test
    fun `redactor preserves structured domain observations while hiding their hosts`() {
        val sensitiveHost = "private.example.test"
        val report =
            ScanReport(
                sessionId = "session-redact-domain",
                profileId = "default",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 1L,
                finishedAt = 2L,
                summary = "complete",
                observations =
                    listOf(
                        ObservationFact(
                            kind = ObservationKind.DOMAIN,
                            target = sensitiveHost,
                            domain =
                                DomainObservationFact(
                                    host = sensitiveHost,
                                    httpStatus = HttpProbeStatus.OK,
                                    tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                                    tlsError = "handshake failed for $sensitiveHost",
                                ),
                        ),
                    ),
            )

        val redacted = requireNotNull(redactor.redact(report.toEngineScanReportWire()))
        val observation = redacted.observations.single()
        val domain = requireNotNull(observation.domain)
        val encoded = json.encodeToString(redacted)

        assertEquals("redacted", observation.target)
        assertEquals("redacted", domain.host)
        assertEquals(HttpProbeStatus.OK, domain.httpStatus)
        assertEquals(TlsProbeStatus.HANDSHAKE_FAILED, domain.tls13Status)
        assertFalse(encoded.contains(sensitiveHost))
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
        val privateKeyStart = listOf("-----BEGIN", "PRIVATE KEY-----").joinToString(" ")
        val privateKeyEnd = listOf("-----END", "PRIVATE KEY-----").joinToString(" ")
        val raw =
            "Authorization: Bearer secret-token " +
                "https://admin:hunter2@private.example.test/api?token=abc123 " +
                "host=private.example.test addr=203.0.113.10 ssid=\"CoffeeShop\" bssid=11:22:33:44:55:66\n" +
                "resolver ::1 fe80::1 2001:db8::53 dns.stable.example path=/data/user/0/com.example/private.txt\n" +
                "operator=Sensitive Mobile Operator; carrier=Sensitive Carrier\n" +
                "$privateKeyStart\nprivate-key-material\n$privateKeyEnd"

        val redacted = DiagnosticsLogRedactor().redactLogcat(raw)

        assertEquals(redactDiagnosticsLogcat(raw), redacted)
        assertFalse("Bearer token must not appear verbatim", redacted.contains("secret-token"))
        assertFalse("URL credential must not appear verbatim", redacted.contains("admin:hunter2"))
        assertFalse("query token must not appear verbatim", redacted.contains("abc123"))
        assertFalse("endpoint host must not appear verbatim", redacted.contains("private.example.test"))
        assertFalse("endpoint address must not appear verbatim", redacted.contains("203.0.113.10"))
        assertFalse("SSID value must not appear verbatim", redacted.contains("CoffeeShop"))
        assertFalse("BSSID must not appear verbatim", redacted.contains("11:22:33:44:55:66"))
        assertFalse("IPv6 must not appear verbatim", redacted.contains("2001:db8::53"))
        assertFalse("IPv6 loopback must not appear verbatim", redacted.contains("::1"))
        assertFalse("IPv6 link-local address must not appear verbatim", redacted.contains("fe80::1"))
        assertFalse("DNS name must not appear verbatim", redacted.contains("dns.stable.example"))
        assertFalse(
            "filesystem path must not appear verbatim",
            redacted.contains("/data/user/0/com.example/private.txt"),
        )
        assertFalse("operator must not appear verbatim", redacted.contains("Sensitive Mobile Operator"))
        assertFalse("carrier must not appear verbatim", redacted.contains("Sensitive Carrier"))
        assertFalse("PEM body must not appear verbatim", redacted.contains("private-key-material"))
        assertTrue("redacted marker must be present", redacted.contains("redacted"))
    }

    @Test
    fun `archive redactor fails closed on truncated pem material`() {
        val privateKeyStart = listOf("-----BEGIN", "PRIVATE KEY-----").joinToString(" ")
        val raw = "before\n$privateKeyStart\ntruncated-private-material\nwithout-end-marker"

        val redacted = redactDiagnosticsArchiveText(raw)

        assertTrue(redacted.startsWith("before\n<pem-redacted>"))
        assertFalse(redacted.contains("truncated-private-material"))
        assertFalse(redacted.contains("without-end-marker"))
    }

    @Test
    fun `archive redactor removes unicode hosts and fails closed on ambiguous path line`() {
        val raw =
            "unicode=пример.рф ideographic=пример。рф fullwidth=пример．рф halfwidth=пример｡рф " +
                "punycode=resolver.xn--p1ai idnaPunycode=resolver。xn--p1ai\n" +
                "file=/data/user/0/My Files/private trace.log; " +
                "file=/storage/emulated/0/John, Doe/private.pem; " +
                "path=/data/private/key: backup.pem; " +
                "file=/data/private/John'Doe/key.pem; " +
                "file=C:\\Users\\Private User\\trace file.log; " +
                "path=C:\\Users\\John,Doe\\key:backup.pem; " +
                "file=C:\\Users\\John\"Doe\\private.pem; status=failed"

        val redacted = redactDiagnosticsArchiveText(raw)

        listOf(
            "пример.рф",
            "пример。рф",
            "пример．рф",
            "пример｡рф",
            "resolver.xn--p1ai",
            "resolver。xn--p1ai",
            "xn--p1ai",
            "/data/user/0/My Files/private trace.log",
            "My Files/private trace.log",
            "C:\\Users\\Private User\\trace file.log",
            "Private User\\trace file.log",
            "/storage/emulated/0/John, Doe/private.pem",
            "John, Doe/private.pem",
            "/data/private/key: backup.pem",
            "key: backup.pem",
            "/data/private/John'Doe/key.pem",
            "John'Doe/key.pem",
            "C:\\Users\\John,Doe\\key:backup.pem",
            "John,Doe\\key:backup.pem",
            "C:\\Users\\John\"Doe\\private.pem",
            "John\"Doe\\private.pem",
        ).forEach { sensitive -> assertFalse(redacted.contains(sensitive)) }
        assertFalse(redacted.contains("status=failed"))
        assertTrue(redacted.endsWith("<path-redacted>"))
    }

    @Test
    fun `archive path redaction fails closed per ambiguous line and preserves valid semantic json fields`() {
        val raw =
            "opened /data/private/key.pem successfully before retry\n" +
                "path=/data/foo;status=failed\n" +
                "{\"path\":\"/data/private/compact,key.pem\",\"status\":\"ready\"}\n" +
                "file='/data/private/My Files/key.pem';status=quoted"

        val redacted = redactDiagnosticsArchiveText(raw)

        assertEquals(
            "<path-redacted>\n" +
                "<path-redacted>\n" +
                "{\"path\":\"<path-redacted>\",\"status\":\"ready\"}\n" +
                "<path-redacted>",
            redacted,
        )
    }

    @Test
    fun `archive path redaction covers absolute path spellings and escape layers`() {
        val pathLines =
            listOf(
                "/data/private/unix-secret.pem",
                "C:\\Users\\Private\\drive-secret.pem",
                "C:/Users/Private/forward-drive-secret.pem",
                "\\\\server\\share\\unc-secret.pem",
                "\\Users\\Private\\root-secret.pem",
                "\\\\?\\C:\\Users\\Private\\extended-secret.pem",
                "\\\\?\\UNC\\server\\share\\extended-unc-secret.pem",
                "\\\\.\\PhysicalDrive0",
                "\\??\\C:\\Users\\Private\\nt-secret.pem",
                "C:/Users\\Private/mixed-secret.pem",
                "\\/storage\\/emulated\\/0\\/escaped-solidus-secret.pem",
                """{"detail":"C:\\Users\\Private\\escaped-backslash-secret.pem"}""",
                """{"detail":"\u002fdata\u002Fprivate\u002funicode-solidus-secret.pem"}""",
                """{"detail":"\u005cUsers\u005CPrivate\u005cunicode-backslash-secret.pem"}""",
                """{"detail":"\\u005cUsers\\u005CPrivate\\u005cnested-unicode-secret.pem"}""",
                """{"detail":"C:\u005cUsers\u005cPrivate\u005cencoded-drive-secret.pem"}""",
                "%2Fprivate%2Fpercent-slash-secret.pem",
                "%5cUsers%5CPrivate%5cpercent-backslash-secret.pem",
                "%252fprivate%252Fpercent-nested-slash-secret.pem",
                "%255CUsers%255cPrivate%255Cpercent-nested-backslash-secret.pem",
                "%25252Fprivate%25252fpercent-double-nested-secret.pem",
                "C:%252FUsers%255cPrivate%2Fpercent-mixed-secret.pem",
            )
        val raw = pathLines.joinToString("\n")

        val redacted = redactDiagnosticsArchiveText(raw)

        assertEquals(List(pathLines.size) { "<path-redacted>" }.joinToString("\n"), redacted)
    }

    @Test
    fun `archive path redaction scans every semantic quoted path key`() {
        val raw =
            """{"file":"/private/file.pem","path":"C:\\Private\\path.pem",""" +
                """"filePath":"%252Fserver%255Cshare%2Ffile-path.pem","status":"ready"}"""

        val redacted = redactDiagnosticsArchiveText(raw)

        assertEquals(
            """{"file":"<path-redacted>","path":"<path-redacted>","filePath":"<path-redacted>","status":"ready"}""",
            redacted,
        )
    }

    @Test
    fun `archive path redaction preserves logical line separators exactly`() {
        val raw =
            "/unix-secret\r\nC:\\Users\\drive-secret\r\\\\server\\share\\unc-secret\n" +
                "%252Fpercent-secret\r\nsafe"

        val redacted = redactDiagnosticsArchiveText(raw)

        assertEquals(
            "<path-redacted>\r\n<path-redacted>\r<path-redacted>\n<path-redacted>\r\nsafe",
            redacted,
        )
    }

    @Test
    fun `archive path redaction retains only strict known app logcat prefixes`() {
        val raw =
            "I/RIPDPI( 123): \\Users\\Private\\root-secret.pem\n" +
                "I/Other( 123): \\Users\\Private\\unknown-tag-secret.pem"

        val redacted = redactDiagnosticsArchiveText(raw)

        assertEquals("I/RIPDPI( 123): <path-redacted>\n<path-redacted>", redacted)
    }

    @Test(timeout = 1_000L)
    fun `archive path redaction handles malformed semantic json in bounded time`() {
        val raw = "{\"path\":\"C:" + "\\".repeat(4_096)

        val redacted = redactDiagnosticsArchiveText(raw)

        assertEquals("<path-redacted>", redacted)
    }

    @Test(timeout = 1_000L)
    fun `archive path redaction handles deeply nested percent encoding in bounded time`() {
        val raw = "%" + "25".repeat(4_096) + "2Fpercent-deep-secret.pem"

        val redacted = redactDiagnosticsArchiveText(raw)

        assertEquals("<path-redacted>", redacted)
    }

    @Test
    fun `archive redactor removes pem tail when begin marker was truncated`() {
        val privateKeyEnd = listOf("-----END", "PRIVATE KEY-----").joinToString(" ")
        val base64Tail = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo="
        val raw = "before\n$base64Tail\n$privateKeyEnd\nafter"

        val redacted = redactDiagnosticsArchiveText(raw)

        assertFalse(redacted.contains(base64Tail))
        assertFalse(redacted.contains(privateKeyEnd))
        assertFalse(redacted.contains("before"))
        assertTrue(redacted.startsWith("<pem-redacted>"))
        assertTrue(redacted.contains("after"))
    }

    @Test
    fun `archive redactor removes short pem tails with fail closed sensitive end policy`() {
        val privateKeyEnd = listOf("-----END", "PRIVATE KEY-----").joinToString(" ")
        val certificateEnd = listOf("-----END", "CERTIFICATE-----").joinToString(" ")
        val shortTails =
            listOf(
                "A" to privateKeyEnd,
                "AB" to certificateEnd,
                "ABC" to privateKeyEnd,
                "ABCD" to certificateEnd,
                "ABCDE" to privateKeyEnd,
                "ABCDEF" to certificateEnd,
                "ABCDEFG" to privateKeyEnd,
                "YQ==" to privateKeyEnd,
                "YWI=" to certificateEnd,
                "eg==" to certificateEnd,
                "aaaa" to privateKeyEnd,
            )

        shortTails.forEach { (tail, endMarker) ->
            val raw = "before:\n$tail\n$endMarker\nafter"
            val redacted = redactDiagnosticsArchiveText(raw)

            assertEquals("before:\n<pem-redacted>\nafter", redacted)
        }

        val failClosed = "ordinary preface: keep\nnote\n$privateKeyEnd\nafter"
        assertEquals("ordinary preface: keep\n<pem-redacted>\nafter", redactDiagnosticsArchiveText(failClosed))

        listOf(
            "before\nordinary text without a marker\nafter",
            "before\nordinary\n-----END TRACE-----\nafter",
        ).forEach { ordinary ->
            assertEquals(ordinary, redactDiagnosticsArchiveText(ordinary))
        }
    }

    @Test
    fun `archive redactor removes head cut multiline pem tail from earliest fragment`() {
        val privateKeyEnd = listOf("-----END", "PRIVATE KEY-----").joinToString(" ")
        val fullLine = "QUJDREVGR0hJSktM"
        val raw = "ordinary preface: keep\nABC\n$fullLine\naaaa\n$privateKeyEnd\nafter"

        val redacted = redactDiagnosticsArchiveText(raw)

        assertEquals("ordinary preface: keep\n<pem-redacted>\nafter", redacted)
    }

    @Test
    fun `archive redactor removes logcat prefixed pem tail`() {
        val privateKeyEnd = listOf("-----END", "PRIVATE KEY-----").joinToString(" ")
        val briefPrefix = "03-12 10:00:00.010 I/RIPDPI: "
        val threadtimePrefix = "03-12 10:00:00.011 123 456 I RIPDPI: "
        val canonicalBriefPrefix = "I/RIPDPI( 123): "
        val fullLine = "QUJDREVGR0hJSktM"
        val raw =
            "before:\n" +
                "${briefPrefix}ABC\n$fullLine\n${threadtimePrefix}aaaa\n$threadtimePrefix$privateKeyEnd\n" +
                "${canonicalBriefPrefix}YQ==\n$canonicalBriefPrefix$privateKeyEnd\n" +
                "after"

        val redacted = redactDiagnosticsLogcat(raw)

        assertFalse(redacted.contains("YQ=="))
        assertFalse(redacted.contains("ABC"))
        assertFalse(redacted.contains("aaaa"))
        assertFalse(redacted.contains(fullLine))
        assertFalse(redacted.contains(privateKeyEnd))
        assertTrue(redacted.contains("before:"))
        assertTrue(redacted.contains("after"))
    }
}
