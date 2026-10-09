package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.diagnostics.DiagnosticsBoundaryMapper
import com.poyka.ripdpi.diagnostics.IpFamilyProbeEvidence
import com.poyka.ripdpi.diagnostics.IpProbeFamily
import com.poyka.ripdpi.diagnostics.IpProbeStage
import com.poyka.ripdpi.diagnostics.IpProbeStatus
import com.poyka.ripdpi.diagnostics.Nat64DiscoveryStatus
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.platform.AndroidStringResolver
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsIpFamilyUiTest {
    private val app = RuntimeEnvironment.getApplication()
    private val strings = AndroidStringResolver(app)
    private val support = DiagnosticsUiCoreSupport(DiagnosticsUiFormatter(), strings)

    @Test
    fun savedResultsPreserveIndependentFamilyFactsWithoutRawJson() {
        val evidence =
            listOf(
                IpFamilyProbeEvidence(
                    family = IpProbeFamily.IPV4,
                    stage = IpProbeStage.TCP_CONNECT,
                    status = IpProbeStatus.REACHABLE,
                    durationMs = 12,
                    attemptCount = 1,
                ),
                IpFamilyProbeEvidence(
                    family = IpProbeFamily.IPV6,
                    stage = IpProbeStage.TCP_CONNECT,
                    status = IpProbeStatus.FAILED,
                    reason = "network_unreachable",
                    attemptCount = 1,
                ),
                IpFamilyProbeEvidence(
                    family = IpProbeFamily.NAT64,
                    stage = IpProbeStage.TCP_CONNECT,
                    status = IpProbeStatus.TIMEOUT,
                    reason = "timeout",
                    attemptCount = 2,
                    discoveryStatus = Nat64DiscoveryStatus.DISCOVERED,
                    prefixLength = 96,
                    resolverSource = "NETWORK_SNAPSHOT",
                ),
            )
        val groups =
            evidence.mapIndexed { index, item ->
                val probe =
                    ProbeResult(
                        "ip_family",
                        item.family.name,
                        "ip_family_inconclusive",
                        listOf(
                            ProbeDetail(
                                "ipFamilyEvidence",
                                Json.encodeToString(IpFamilyProbeEvidence.serializer(), item),
                            ),
                        ),
                    )
                val entity =
                    ProbeResultEntity(
                        "ip-$index",
                        "scan-1",
                        probe.probeType,
                        probe.target,
                        probe.outcome,
                        Json.encodeToString(ListSerializer(ProbeDetail.serializer()), probe.details),
                        1000,
                    )
                val restored = DiagnosticsBoundaryMapper(Json).toProbeResult(entity)
                val storedUi = support.toProbeResultUiModel(index, ScanPathMode.RAW_PATH, restored)
                assertEquals(
                    support.toProbeResultUiModel(index, ScanPathMode.RAW_PATH, probe).ipFamily,
                    storedUi.ipFamily,
                )
                assertTrue(storedUi.details.isEmpty())
                checkNotNull(storedUi.ipFamily)
            }
        assertEquals(3, groups.map { it.title }.toSet().size)
        assertEquals(app.getString(R.string.diagnostics_ip_reachable), groups[0].value(R.string.diagnostics_ip_status))
        assertEquals(app.getString(R.string.diagnostics_ip_failed), groups[1].value(R.string.diagnostics_ip_status))
        assertEquals(app.getString(R.string.diagnostics_ip_timeout), groups[2].value(R.string.diagnostics_ip_status))
        assertEquals(
            app.getString(R.string.diagnostics_ip_discovered),
            groups[2].value(R.string.diagnostics_ip_discovery),
        )
        assertEquals("96", groups[2].value(R.string.diagnostics_ip_prefix_length))
        assertTrue(groups[0].fields.any { it.value == app.getString(R.string.diagnostics_ip_clat_caution) })
        assertTrue(groups[2].fields.any { it.value == app.getString(R.string.diagnostics_ip_nat64_caution) })
    }

    @Test
    fun legacyMalformedAndDuplicateEvidenceStayUnknownAndNeverExposePayload() {
        listOf(
            emptyList(),
            listOf(ProbeDetail("ipFamilyEvidence", "{secret}")),
            listOf(ProbeDetail("ipFamilyEvidence", "{}"), ProbeDetail("ipFamilyEvidence", "{}")),
        ).forEach { details ->
            val ui =
                support.toProbeResultUiModel(
                    0,
                    ScanPathMode.RAW_PATH,
                    ProbeResult("ip_family", "IPv6", "ip_family_unavailable", details),
                )
            assertTrue(ui.details.isEmpty())
            assertEquals(app.getString(R.string.diagnostics_ip_missing), checkNotNull(ui.ipFamily).fields.first().value)
            assertFalse(ui.ipFamily.toString().contains("secret"))
        }
        assertNull(ProbeResult("tcp", "target", "tcp_ok").toIpFamilyGroup(strings))
    }

    @Test
    fun discoveryWithoutConnectionCannotBePresentedAsReachable() {
        val evidence =
            IpFamilyProbeEvidence(
                family = IpProbeFamily.NAT64,
                stage = IpProbeStage.DNS64_DISCOVERY,
                status = IpProbeStatus.NOT_OBSERVED,
                discoveryStatus = Nat64DiscoveryStatus.DISCOVERED,
                prefixLength = 64,
            )
        val group = ipFamilyGroup(strings, evidence)
        assertEquals(app.getString(R.string.diagnostics_ip_missing), group.value(R.string.diagnostics_ip_status))
        assertEquals(app.getString(R.string.diagnostics_ip_dns64), group.value(R.string.diagnostics_ip_stage))
        assertFalse(group.fields.any { it.value == app.getString(R.string.diagnostics_ip_reachable) })
    }

    @Test
    fun historicalEvidenceRetainsNetworkChangeCaution() {
        val group = ipFamilyGroup(strings, null, networkScopeUnverified = true)
        assertTrue(group.fields.any { it.value == app.getString(R.string.diagnostics_ip_network_changed) })
    }

    @Test
    fun statusesHaveDistinctLocalizedLabels() {
        assertEquals(
            IpProbeStatus.entries.size,
            IpProbeStatus.entries
                .map { app.getString(it.labelResource()) }
                .toSet()
                .size,
        )
    }

    private fun DiagnosticsContextGroupUiModel.value(resource: Int): String =
        fields.single { it.label == app.getString(resource) }.value
}
