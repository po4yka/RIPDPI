package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.diagnostics.DiagnosticsBoundaryMapper
import com.poyka.ripdpi.diagnostics.DnsResponseOutcome
import com.poyka.ripdpi.diagnostics.DnsResponseSemantics
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityDomainResult
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityResult
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityVerdict
import com.poyka.ripdpi.diagnostics.dpi.DnsServerResult
import com.poyka.ripdpi.diagnostics.dpi.DnsServerType
import com.poyka.ripdpi.platform.AndroidStringResolver
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsDnsResponseUiTest {
    private val app = RuntimeEnvironment.getApplication()
    private val strings = AndroidStringResolver(app)

    @Test
    fun legacyAndMalformedDnsEvidenceRemainExplicitlyMissing() {
        listOf(emptyList(), listOf(ProbeDetail("udpDnsResponse", "{broken}"))).forEach { details ->
            val groups = ProbeResult("dns_integrity", "example.test", "dns_match", details).toDnsResponseGroups(strings)
            assertEquals(2, groups.size)
            groups.forEach { group ->
                assertTrue(group.stackedFields)
                assertEquals(app.getString(R.string.diagnostics_dns_missing), group.fields.first().value)
            }
        }
        assertTrue(ProbeResult("tcp", "example.test", "tcp_ok").toDnsResponseGroups(strings).isEmpty())
    }

    @Test
    fun storedNativeDnsResultProjectsCurrentAndHistoryFactsWithoutRawJson() {
        val response =
            DnsResponseSemantics(
                outcome = DnsResponseOutcome.NXDOMAIN,
                rcode = 3,
                negativeTtlSeconds = 60,
                truncated = false,
                authenticatedData = true,
                hasSoa = true,
                cnameTargets = listOf("alias.example.test"),
                extendedDnsErrorCodes = listOf(15, 16),
            )
        val probe =
            ProbeResult(
                "dns_integrity",
                "example.test",
                "dns_mismatch",
                listOf(
                    ProbeDetail("udpDnsResponse", Json.encodeToString(DnsResponseSemantics.serializer(), response)),
                    ProbeDetail(
                        "encryptedDnsResponse",
                        Json.encodeToString(
                            DnsResponseSemantics.serializer(),
                            response.copy(outcome = DnsResponseOutcome.ANSWER, rcode = 0, negativeTtlSeconds = null),
                        ),
                    ),
                    ProbeDetail("udpServer", "192.0.2.1"),
                ),
            )
        val stored =
            ProbeResultEntity(
                id = "dns-observation",
                sessionId = "scan-1",
                probeType = probe.probeType,
                target = probe.target,
                outcome = probe.outcome,
                detailJson = Json.encodeToString(ListSerializer(ProbeDetail.serializer()), probe.details),
                createdAt = 1_000,
            )
        val restored = DiagnosticsBoundaryMapper(Json).toProbeResult(stored)
        val ui =
            DiagnosticsUiCoreSupport(
                DiagnosticsUiFormatter(),
                strings,
            ).toProbeResultUiModel(0, ScanPathMode.RAW_PATH, restored)
        val current =
            DiagnosticsUiCoreSupport(DiagnosticsUiFormatter(), strings)
                .toProbeResultUiModel(0, ScanPathMode.RAW_PATH, probe)
        assertEquals(current.dnsResponses, ui.dnsResponses)
        assertEquals(listOf("udpServer"), ui.details.map { it.label })
        assertEquals(2, ui.dnsResponses.size)
        val udp = ui.dnsResponses.first()
        assertEquals("3", udp.value(R.string.diagnostics_dns_rcode))
        assertEquals("60", udp.value(R.string.diagnostics_dns_negative_ttl))
        assertEquals("15, 16", udp.value(R.string.diagnostics_dns_ede))
        assertEquals(app.getString(R.string.diagnostics_dns_yes), udp.value(R.string.diagnostics_dns_ad))
        assertEquals(app.getString(R.string.diagnostics_dns_unknown), udp.value(R.string.diagnostics_dns_ttl_min))
        assertEquals("0", ui.dnsResponses.last().value(R.string.diagnostics_dns_rcode))
        assertFalse(ui.details.any { it.value.contains("authenticatedData") })
    }

    @Test
    fun toolShowsSourceEvidenceWithoutTurningLegacyEmptyListsIntoTimeouts() {
        val result =
            DnsIntegrityResult(
                listOf(
                    DnsIntegrityDomainResult(
                        "example.test",
                        DnsIntegrityVerdict.DNS_DISAGREEMENT,
                        emptyList(),
                        emptySet(),
                        emptySet(),
                    ),
                ),
                emptySet(),
                0,
            ).toUiModel(strings)
        val row = result.rows.single()
        assertEquals(app.getString(R.string.diagnostics_dns_disagreement), row.verdict)
        assertEquals(app.getString(R.string.diagnostics_dns_missing), row.udpAnswer)
        assertEquals(3, row.dnsResponses.size)
    }

    @Test
    fun availabilityKeepsNegativeResponseFactsSeparateFromAddressAvailability() {
        val result =
            listOf(
                DnsServerResult(
                    "Test resolver",
                    DnsServerType.UDP,
                    0,
                    1,
                    null,
                    mapOf("example.test" to DnsResponseSemantics(outcome = DnsResponseOutcome.REFUSED, rcode = 5)),
                ),
            ).toDnsAvailabilityUiModel(strings)
        val row = result.rows.single()
        assertEquals("0/1", row.availability)
        assertEquals(app.getString(R.string.diagnostics_dns_unknown), row.latency)
        assertEquals("5", row.dnsResponses.single().value(R.string.diagnostics_dns_rcode))
        assertTrue(
            row.dnsResponses
                .single()
                .title
                .contains("example.test"),
        )
    }

    @Test
    fun allOutcomesHaveDistinctLocalizedLabelsAndInvalidFactsAreNotShown() {
        assertEquals(
            DnsResponseOutcome.entries.size,
            DnsResponseOutcome.entries
                .map {
                    app.getString(dnsOutcomeLabel(it))
                }.toSet()
                .size,
        )
        val invalid = dnsResponseGroup(strings, R.string.diagnostics_dns_source_udp, DnsResponseSemantics(rcode = -1))
        assertEquals(app.getString(R.string.diagnostics_dns_missing), invalid.fields.first().value)
    }

    private fun DiagnosticsContextGroupUiModel.value(label: Int): String =
        fields
            .single {
                it.label ==
                    app.getString(label)
            }.value
}
