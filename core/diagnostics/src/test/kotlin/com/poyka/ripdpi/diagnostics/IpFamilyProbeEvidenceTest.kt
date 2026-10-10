package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRedactor
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class IpFamilyProbeEvidenceTest {
    private val evidence =
        IpFamilyProbeEvidence(
            family = IpProbeFamily.NAT64,
            stage = IpProbeStage.TCP_CONNECT,
            status = IpProbeStatus.TIMEOUT,
            reason = "timeout",
            durationMs = 1500,
            destinationAddress = "64:ff9b::101:101",
            prefix = "64:ff9b::",
            prefixLength = 96,
            discoveryStatus = Nat64DiscoveryStatus.DISCOVERED,
            resolverSource = "NETWORK_SNAPSHOT",
            attemptCount = 1,
        )

    @Test
    fun `discovery is preserved separately from a failed translation`() {
        assertEquals(
            evidence,
            parseIpFamilyProbeEvidence(Json.encodeToString(IpFamilyProbeEvidence.serializer(), evidence)),
        )
        assertNull(
            evidence
                .copy(
                    status = IpProbeStatus.REACHABLE,
                    stage = IpProbeStage.DNS64_DISCOVERY,
                ).validatedIpFamilyProbeEvidence(),
        )
        assertNull(
            evidence
                .copy(
                    status = IpProbeStatus.REACHABLE,
                    discoveryStatus = Nat64DiscoveryStatus.NO_PREFIX,
                ).validatedIpFamilyProbeEvidence(),
        )
    }

    @Test
    fun `metadata fails closed for bounds enums duplicate keys and family mismatch`() {
        assertNull(parseIpFamilyProbeEvidence("{}"))
        assertNull(parseIpFamilyProbeEvidence("not-json"))
        assertNull(evidence.copy(version = 2).validatedIpFamilyProbeEvidence())
        assertNull(evidence.copy(prefixLength = 97).validatedIpFamilyProbeEvidence())
        assertNull(evidence.copy(attemptCount = 17).validatedIpFamilyProbeEvidence())
        assertNull(evidence.copy(durationMs = -1).validatedIpFamilyProbeEvidence())
        assertNull(evidence.copy(reason = "private-hostname").validatedIpFamilyProbeEvidence())
        assertNull(evidence.copy(destinationAddress = "example.com").validatedIpFamilyProbeEvidence())
        assertNull(evidence.copy(family = IpProbeFamily.IPV4).validatedIpFamilyProbeEvidence())
        val detail = ProbeDetail("ipFamilyEvidence", Json.encodeToString(IpFamilyProbeEvidence.serializer(), evidence))
        assertNull(parseIpFamilyProbeEvidence(listOf(detail, detail)))
    }

    @Test
    fun `archive removes nested addresses prefixes and unknown free text without losing facts`() {
        val raw =
            Json.encodeToString(IpFamilyProbeEvidence.serializer(), evidence).dropLast(1) +
                ",\"extraText\":\"private-canary\"}"
        val entity =
            ProbeResultEntity(
                "result",
                "session",
                "ip_family",
                "NAT64",
                "ip_family_unavailable",
                Json.encodeToString(
                    ListSerializer(ProbeDetail.serializer()),
                    listOf(ProbeDetail("ipFamilyEvidence", raw)),
                ),
                1,
            )
        val exported = DiagnosticsArchiveRedactor(Json).redact(entity)
        assertFalse(exported.detailJson.contains("private-canary"))
        assertFalse(exported.detailJson.contains("64:ff9b"))
        val decoded = Json.decodeFromString(ListSerializer(ProbeDetail.serializer()), exported.detailJson)
        val safe = requireNotNull(parseIpFamilyProbeEvidence(decoded))
        assertEquals(IpProbeStatus.TIMEOUT, safe.status)
        assertEquals(Nat64DiscoveryStatus.DISCOVERED, safe.discoveryStatus)
        assertEquals(96, safe.prefixLength)
        assertNull(safe.destinationAddress)
        assertNull(safe.prefix)
    }

    @Test
    fun `connection scale never promotes prefix discovery into TCP success`() {
        val discovered = evidence.copy(stage = IpProbeStage.DNS64_DISCOVERY, status = IpProbeStatus.NOT_OBSERVED)
        val result =
            ProbeResult(
                "ip_family",
                "NAT64",
                "ip_family_inconclusive",
                listOf(
                    ProbeDetail(
                        "ipFamilyEvidence",
                        Json.encodeToString(IpFamilyProbeEvidence.serializer(), discovered),
                    ),
                ),
            )
        val stages = requireNotNull(result.toConnectionStageScale()).lanes.single().stages
        assertEquals(ConnectionStageState.SUCCEEDED, stages.single { it.stage == ConnectionStage.DNS }.state)
        assertEquals(ConnectionStageState.NOT_REACHED, stages.single { it.stage == ConnectionStage.TCP }.state)
        assertEquals(ConnectionStageState.NOT_APPLICABLE, stages.single { it.stage == ConnectionStage.TLS }.state)
    }

    @Test
    fun `NAT64 total elapsed time is not a TCP duration and missing evidence stays unknown`() {
        val detail = ProbeDetail("ipFamilyEvidence", Json.encodeToString(IpFamilyProbeEvidence.serializer(), evidence))
        val tcp = ipFamilyConnectionLane(listOf(detail)).stages.single { it.stage == ConnectionStage.TCP }
        assertNull(tcp.durationMs)
        assertEquals(1500L, tcp.elapsedMs)
        val unknown = ipFamilyConnectionLane(emptyList()).stages.single { it.stage == ConnectionStage.TCP }
        assertEquals(ConnectionStageState.UNKNOWN, unknown.state)
    }

    @Test
    fun `summary contains measured facts and no endpoint`() {
        val result =
            ProbeResult(
                "ip_family",
                "NAT64",
                "ip_family_unavailable",
                listOf(
                    ProbeDetail("ipFamilyEvidence", Json.encodeToString(IpFamilyProbeEvidence.serializer(), evidence)),
                ),
            )
        val summary = listOf(result).ipFamilySummaryLines().single()
        assertFalse(summary.contains("64:ff9b"))
        org.junit.Assert.assertTrue(summary.contains("status=TIMEOUT"))
        org.junit.Assert.assertTrue(summary.contains("providerInterference=unverified"))
    }
}
