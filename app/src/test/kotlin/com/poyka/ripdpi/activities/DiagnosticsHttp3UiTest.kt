package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.diagnostics.DiagnosticsBoundaryMapper
import com.poyka.ripdpi.diagnostics.Http3ProbeEvidence
import com.poyka.ripdpi.diagnostics.Http3ProbeStage
import com.poyka.ripdpi.diagnostics.Http3ProbeStatus
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
class DiagnosticsHttp3UiTest {
    private val app = RuntimeEnvironment.getApplication()
    private val strings = AndroidStringResolver(app)
    private val support = DiagnosticsUiCoreSupport(DiagnosticsUiFormatter(), strings)

    @Test
    fun savedHttpErrorStillConfirmsProtocolAndHidesPeerAddress() {
        val evidence = response().copy(status = Http3ProbeStatus.HTTP_ERROR, httpStatus = 403)
        val probe = probe(evidence)
        val entity =
            ProbeResultEntity(
                "http3-1",
                "scan-1",
                probe.probeType,
                probe.target,
                probe.outcome,
                Json.encodeToString(ListSerializer(ProbeDetail.serializer()), probe.details),
                1000,
            )
        val restored = DiagnosticsBoundaryMapper(Json).toProbeResult(entity)
        val ui = support.toProbeResultUiModel(0, ScanPathMode.RAW_PATH, restored)
        val group = checkNotNull(ui.http3)
        assertEquals(support.toProbeResultUiModel(0, ScanPathMode.RAW_PATH, probe).http3, group)
        assertEquals(
            app.getString(R.string.diagnostics_http3_observed),
            group.value(R.string.diagnostics_http3_validated),
        )
        assertEquals(app.getString(R.string.diagnostics_http3_http_error), group.value(R.string.diagnostics_ip_status))
        assertEquals("403", group.value(R.string.diagnostics_http3_status_code))
        assertTrue(ui.details.isEmpty())
        assertFalse(group.toString().contains("2001:db8::42"))
    }

    @Test
    fun handshakeAloneDoesNotConfirmHttp3OrBody() {
        val evidence =
            Http3ProbeEvidence(
                stage = Http3ProbeStage.HTTP_REQUEST,
                status = Http3ProbeStatus.FAILED,
                tlsValidated = true,
                alpn = "h3",
                attemptCount = 1,
                handshakeElapsedMs = 50,
            )
        val group = checkNotNull(probe(evidence).toHttp3Group(strings))
        assertEquals(app.getString(R.string.diagnostics_http3_observed), group.value(R.string.diagnostics_http3_tls))
        assertEquals(
            app.getString(R.string.diagnostics_http3_not_observed),
            group.value(R.string.diagnostics_http3_validated),
        )
        assertEquals(
            app.getString(R.string.diagnostics_http3_not_observed),
            group.value(R.string.diagnostics_http3_complete),
        )
    }

    @Test
    fun partialBodyKeepsProtocolFactAndTransferLimitDistinct() {
        val evidence =
            response().copy(
                status = Http3ProbeStatus.BODY_LIMIT,
                bodyComplete = false,
                reason = "body_limit",
            )
        val group = checkNotNull(probe(evidence).toHttp3Group(strings))
        assertEquals(
            app.getString(R.string.diagnostics_http3_observed),
            group.value(R.string.diagnostics_http3_validated),
        )
        assertEquals(
            app.getString(R.string.diagnostics_http3_not_observed),
            group.value(R.string.diagnostics_http3_complete),
        )
        assertEquals(app.getString(R.string.diagnostics_http3_body_limit), group.value(R.string.diagnostics_ip_status))
        assertEquals("42", group.value(R.string.diagnostics_http3_bytes))
    }

    @Test
    fun missingMalformedAndDuplicateEvidenceStayUnknown() {
        listOf(
            emptyList(),
            listOf(ProbeDetail("http3Evidence", "{secret}")),
            listOf(ProbeDetail("http3Evidence", "{}"), ProbeDetail("http3Evidence", "{}")),
        ).forEach { details ->
            val ui =
                support.toProbeResultUiModel(
                    0,
                    ScanPathMode.RAW_PATH,
                    ProbeResult("http3", "control", "http3_complete", details),
                )
            assertTrue(ui.details.isEmpty())
            assertEquals(app.getString(R.string.diagnostics_ip_missing), checkNotNull(ui.http3).fields.first().value)
            assertFalse(ui.http3.toString().contains("secret"))
        }
        assertNull(ProbeResult("quic", "control", "quic_initial_response").toHttp3Group(strings))
    }

    @Test
    fun changedNetworkCannotPresentHistoricalProtocolFactsAsVerifiedScope() {
        val evidence = response().copy(status = Http3ProbeStatus.NOT_OBSERVED, reason = "network_scope_unverified")
        val group = checkNotNull(probe(evidence).toHttp3Group(strings))
        assertEquals(app.getString(R.string.diagnostics_ip_missing), group.value(R.string.diagnostics_ip_status))
        assertTrue(group.fields.any { it.value == app.getString(R.string.diagnostics_ip_network_changed) })
        assertTrue(
            http3Group(strings, null, true).fields.any {
                it.value ==
                    app.getString(R.string.diagnostics_ip_network_changed)
            },
        )
    }

    private fun response() =
        Http3ProbeEvidence(
            stage = Http3ProbeStage.HTTP_BODY,
            status = Http3ProbeStatus.COMPLETE,
            peerAddress = "2001:db8::42",
            alpn = "h3",
            tlsValidated = true,
            http3Validated = true,
            requestSent = true,
            httpStatus = 200,
            responseBytes = 42,
            bodyComplete = true,
            attemptCount = 1,
            durationMs = 120,
            handshakeElapsedMs = 40,
            headersElapsedMs = 80,
            firstByteElapsedMs = 90,
        )

    private fun probe(evidence: Http3ProbeEvidence) =
        ProbeResult(
            "http3",
            "control",
            "http3_inconclusive",
            listOf(ProbeDetail("http3Evidence", Json.encodeToString(Http3ProbeEvidence.serializer(), evidence))),
        )

    private fun DiagnosticsContextGroupUiModel.value(resource: Int): String =
        fields
            .single {
                it.label ==
                    app.getString(resource)
            }.value
}
