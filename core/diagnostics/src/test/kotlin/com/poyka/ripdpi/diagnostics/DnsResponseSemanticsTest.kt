package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRedactor
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DnsResponseSemanticsTest {
    @Test
    fun `legacy observations omit new fields even with default encoding`() {
        val json = Json { encodeDefaults = true }
        val fact = DnsObservationFact("example.test", DnsObservationStatus.UNAVAILABLE)
        val encoded = json.encodeToString(DnsObservationFact.serializer(), fact)
        assertFalse(encoded.contains("\"udpResponse\""))
        assertFalse(encoded.contains("\"encryptedResponse\""))
        assertNull(json.decodeFromString(DnsObservationFact.serializer(), encoded).udpResponse)
    }

    @Test
    fun `metadata rejects malformed duplicate and out of bounds values`() {
        assertNull(parseDnsResponseSemantics("not-json"))
        assertNull(parseDnsResponseSemantics("""{"outcome":"FUTURE"}"""))
        assertNull(parseDnsResponseSemantics("""{"rcode":4096}"""))
        assertNull(parseDnsResponseSemantics("""{"ttlMinSeconds":-1}"""))
        assertNull(parseDnsResponseSemantics("""{"ttlMaxSeconds":4294967296}"""))
        assertNull(parseDnsResponseSemantics("""{"ttlMinSeconds":2,"ttlMaxSeconds":1}"""))
        assertNull(parseDnsResponseSemantics("""{"queryType":"TXT"}"""))
        assertNull(parseDnsResponseSemantics("""{"cnameTargets":["bad\nname"]}"""))
        val detail = ProbeDetail("udpDnsResponse", """{"outcome":"NODATA","rcode":0}""")
        assertNull(parseDnsResponseSemantics(listOf(detail, detail), detail.key))
    }

    @Test
    fun `unsigned TTL and numeric extended error round trip`() {
        val original =
            DnsResponseSemantics(
                queryType = "AAAA",
                outcome = DnsResponseOutcome.NXDOMAIN,
                rcode = 3,
                negativeTtlSeconds = 4_294_967_295,
                hasSoa = true,
                cnameTargets = listOf("alias.example"),
                extendedDnsErrorCodes = listOf(15, 65535),
            )
        assertEquals(
            original,
            parseDnsResponseSemantics(Json.encodeToString(DnsResponseSemantics.serializer(), original)),
        )
    }

    @Test
    fun `structured report preserves EDE codes and redacts aliases`() {
        val semantics =
            DnsResponseSemantics(
                outcome = DnsResponseOutcome.NXDOMAIN,
                rcode = 3,
                cnameTargets = listOf("private.example"),
                extendedDnsErrorCodes = listOf(15),
            )
        val report =
            EngineScanReportWire(
                sessionId = "session",
                profileId = "profile",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 1,
                finishedAt = 2,
                summary = "DNS",
                observations =
                    listOf(
                        ObservationFact(
                            kind = ObservationKind.DNS,
                            target = "private.example",
                            dns =
                                DnsObservationFact(
                                    "private.example",
                                    DnsObservationStatus.UNAVAILABLE,
                                    udpResponse = semantics,
                                ),
                        ),
                    ),
            )
        val archived = requireNotNull(DiagnosticsArchiveRedactor(Json).redact(report))
        val evidence =
            requireNotNull(
                archived.observations
                    .single()
                    .dns
                    ?.udpResponse,
            )
        assertEquals(listOf(15), evidence.extendedDnsErrorCodes)
        assertEquals(listOf("redacted"), evidence.cnameTargets)
        assertEquals(3, evidence.rcode)
    }

    @Test
    fun `text summary preserves response kind without hostnames`() {
        val semantics =
            DnsResponseSemantics(
                outcome = DnsResponseOutcome.SERVFAIL,
                rcode = 2,
                cnameTargets = listOf("private.example"),
            )
        val result =
            ProbeResult(
                "dns_integrity",
                "private.example",
                "dns_unavailable",
                listOf(
                    ProbeDetail("udpDnsResponse", Json.encodeToString(DnsResponseSemantics.serializer(), semantics)),
                ),
            )
        val line = dnsSemanticsSummaryLines(listOf(result)).single()
        assertFalse(line.contains("private.example"))
        org.junit.Assert.assertTrue(line.contains("outcome=SERVFAIL"))
        org.junit.Assert.assertTrue(line.contains("providerInterference=unverified"))
    }

    @Test
    fun `archive canonicalizes nested DNS evidence and removes free text`() {
        val raw =
            """
            {"queryType":"A","outcome":"REFUSED","rcode":5,"cnameTargets":["alias.example"],
            "extendedDnsErrorCodes":[15],"extraText":"private-canary"}
            """.trimIndent()
        val details = listOf(ProbeDetail("udpDnsResponse", raw))
        val entity =
            ProbeResultEntity(
                "result",
                "session",
                "dns_integrity",
                "private.example",
                "dns_unavailable",
                Json.encodeToString(ListSerializer(ProbeDetail.serializer()), details),
                1,
            )
        val exported = DiagnosticsArchiveRedactor(Json).redact(entity)
        assertFalse(exported.detailJson.contains("private-canary"))
        assertFalse(exported.detailJson.contains("alias.example"))
        val decoded = Json.decodeFromString(ListSerializer(ProbeDetail.serializer()), exported.detailJson)
        val semantics = requireNotNull(parseDnsResponseSemantics(decoded, "udpDnsResponse"))
        assertEquals(DnsResponseOutcome.REFUSED, semantics.outcome)
        assertEquals(listOf(15), semantics.extendedDnsErrorCodes)
        assertEquals(listOf("redacted"), semantics.cnameTargets)
    }
}
