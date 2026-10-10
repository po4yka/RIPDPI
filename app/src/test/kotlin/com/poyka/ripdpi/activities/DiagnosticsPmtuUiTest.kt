package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.diagnostics.DiagnosticsBoundaryMapper
import com.poyka.ripdpi.diagnostics.PmtuAddressFamily
import com.poyka.ripdpi.diagnostics.PmtuProbeEvidence
import com.poyka.ripdpi.diagnostics.PmtuProbeStatus
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
class DiagnosticsPmtuUiTest {
    private val app = RuntimeEnvironment.getApplication()
    private val strings = AndroidStringResolver(app)
    private val support = DiagnosticsUiCoreSupport(DiagnosticsUiFormatter(), strings)

    @Test
    fun historyRetainsLowerBoundAndCeilingSeparatelyWithoutRawPeer() {
        val probe = probe(observed())
        val entity =
            ProbeResultEntity(
                "pmtu-1",
                "scan-1",
                probe.probeType,
                probe.target,
                probe.outcome,
                Json.encodeToString(ListSerializer(ProbeDetail.serializer()), probe.details),
                1000,
            )
        val restored = DiagnosticsBoundaryMapper(Json).toProbeResult(entity)
        val ui = support.toProbeResultUiModel(0, ScanPathMode.RAW_PATH, restored)
        val group = checkNotNull(ui.pmtu)
        assertEquals(support.toProbeResultUiModel(0, ScanPathMode.RAW_PATH, probe).pmtu, group)
        assertEquals(
            app.getString(R.string.diagnostics_pmtu_bytes, 1400),
            group.value(R.string.diagnostics_pmtu_lower_bound),
        )
        assertEquals(
            app.getString(R.string.diagnostics_pmtu_bytes, 1472),
            group.value(R.string.diagnostics_pmtu_ceiling),
        )
        assertTrue(ui.details.isEmpty())
        assertTrue(group.stackedFields)
        assertFalse(group.toString().contains("2001:db8::42"))
    }

    @Test
    fun noAckAndPartialObservationsDoNotConfirmCompletedMeasurement() {
        val noAck =
            PmtuProbeEvidence(
                addressFamily = PmtuAddressFamily.IPV4,
                status = PmtuProbeStatus.INCONCLUSIVE,
                reason = "no_acknowledged_probe",
            )
        val group = checkNotNull(probe(noAck).toPmtuGroup(strings))
        assertEquals(app.getString(R.string.diagnostics_ip_unknown), group.value(R.string.diagnostics_pmtu_lower_bound))
        assertEquals(app.getString(R.string.diagnostics_pmtu_inconclusive), group.value(R.string.diagnostics_ip_status))
        assertEquals(app.getString(R.string.diagnostics_pmtu_ipv4), group.title)
        listOf(PmtuProbeStatus.CANCELLED, PmtuProbeStatus.TIMEOUT).forEach { status ->
            val partial = observed().copy(status = status, observationWindowComplete = false)
            val partialGroup = checkNotNull(probe(partial).toPmtuGroup(strings))
            assertEquals(app.getString(status.pmtuLabelResource()), partialGroup.value(R.string.diagnostics_ip_status))
            assertEquals(
                app.getString(R.string.diagnostics_http3_not_observed),
                partialGroup.value(R.string.diagnostics_pmtu_window),
            )
            assertEquals(
                app.getString(R.string.diagnostics_pmtu_bytes, 1400),
                partialGroup.value(R.string.diagnostics_pmtu_lower_bound),
            )
        }
    }

    @Test
    fun missingMalformedAndDuplicateEvidenceStayUnknown() {
        listOf(
            emptyList(),
            listOf(ProbeDetail("pmtuEvidence", "{secret}")),
            listOf(ProbeDetail("pmtuEvidence", "{}"), ProbeDetail("pmtuEvidence", "{}")),
        ).forEach { details ->
            val ui =
                support.toProbeResultUiModel(
                    0,
                    ScanPathMode.RAW_PATH,
                    ProbeResult("pmtu", "control", "pmtu_observed", details),
                )
            assertTrue(ui.details.isEmpty())
            assertEquals(app.getString(R.string.diagnostics_ip_missing), checkNotNull(ui.pmtu).fields.first().value)
            assertFalse(ui.pmtu.toString().contains("secret"))
        }
        assertNull(ProbeResult("quic", "control", "quic_initial_response").toPmtuGroup(strings))
    }

    @Test
    fun changedNetworkAndSearchLossKeepExplicitLimitations() {
        val evidence = observed().copy(status = PmtuProbeStatus.NOT_OBSERVED, reason = "network_scope_unverified")
        val group = checkNotNull(probe(evidence).toPmtuGroup(strings))
        assertEquals(app.getString(R.string.diagnostics_ip_missing), group.value(R.string.diagnostics_ip_status))
        assertEquals(app.getString(R.string.diagnostics_pmtu_ipv6), group.title)
        assertEquals("3", group.value(R.string.diagnostics_pmtu_lost))
        assertTrue(group.fields.any { it.value == app.getString(R.string.diagnostics_ip_network_changed) })
        assertTrue(group.fields.any { it.value == app.getString(R.string.diagnostics_pmtu_loss_caution) })
        assertTrue(
            pmtuGroup(strings, null, true).fields.any {
                it.value ==
                    app.getString(R.string.diagnostics_ip_network_changed)
            },
        )
    }

    private fun observed() =
        PmtuProbeEvidence(
            addressFamily = PmtuAddressFamily.IPV6,
            status = PmtuProbeStatus.OBSERVED,
            peerAddress = "2001:db8::42",
            alpn = "h3",
            tlsValidated = true,
            fragmentationPrevented = true,
            currentUdpPayloadBytes = 1400,
            acknowledgedUdpPayloadLowerBoundBytes = 1400,
            sentProbeCount = 6,
            lostProbeCount = 3,
            observationWindowComplete = true,
            durationMs = 3500,
            handshakeElapsedMs = 100,
        )

    private fun probe(evidence: PmtuProbeEvidence) =
        ProbeResult(
            "pmtu",
            "control",
            "pmtu_inconclusive",
            listOf(ProbeDetail("pmtuEvidence", Json.encodeToString(PmtuProbeEvidence.serializer(), evidence))),
        )

    private fun DiagnosticsContextGroupUiModel.value(resource: Int): String =
        fields.single { it.label == app.getString(resource) }.value
}
