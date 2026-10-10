package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRedactor
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Http3ProbeEvidenceTest {
    private val evidence =
        Http3ProbeEvidence(
            stage = Http3ProbeStage.HTTP_BODY,
            status = Http3ProbeStatus.COMPLETE,
            dnsStatus = Http3DnsStatus.RESOLVED,
            peerAddress = "192.0.2.1",
            alpn = "h3",
            tlsValidated = true,
            http3Validated = true,
            requestSent = true,
            httpStatus = 200,
            responseBytes = 64,
            bodyComplete = true,
            attemptCount = 1,
            handshakeElapsedMs = 10,
            headersElapsedMs = 20,
            firstByteElapsedMs = 30,
            durationMs = 40,
        )

    @Test
    fun `only measured consistent protocol facts are admitted`() {
        assertEquals(evidence, parseHttp3ProbeEvidence(encode(evidence)))
        assertNull(evidence.copy(tlsValidated = false).validatedHttp3ProbeEvidence())
        assertNull(evidence.copy(alpn = "h2").validatedHttp3ProbeEvidence())
        assertNull(evidence.copy(requestSent = false).validatedHttp3ProbeEvidence())
        assertNull(evidence.copy(httpStatus = null).validatedHttp3ProbeEvidence())
        assertNull(evidence.copy(bodyComplete = false).validatedHttp3ProbeEvidence())
        assertNull(evidence.copy(headersElapsedMs = 5).validatedHttp3ProbeEvidence())
        assertNull(evidence.copy(attemptCount = 0).validatedHttp3ProbeEvidence())
        assertNull(evidence.copy(responseBytes = Long.MAX_VALUE).validatedHttp3ProbeEvidence())
        assertNull(evidence.copy(reason = "private-error-canary").validatedHttp3ProbeEvidence())
        assertNull(parseHttp3ProbeEvidence(listOf(detail(evidence), detail(evidence))))
        assertNull(parseHttp3ProbeEvidence("{}"))
        assertNull(parseHttp3ProbeEvidence(" ".repeat(4097)))
    }

    @Test
    fun `contradictory stages and unsupported success cannot become observations`() {
        listOf(
            evidence.copy(status = Http3ProbeStatus.FAILED, stage = Http3ProbeStage.DNS),
            evidence.copy(status = Http3ProbeStatus.TIMEOUT, stage = Http3ProbeStage.QUIC_HANDSHAKE),
            evidence.copy(status = Http3ProbeStatus.FAILED, stage = Http3ProbeStage.HTTP_REQUEST),
            evidence.copy(status = Http3ProbeStatus.FAILED, stage = Http3ProbeStage.HTTP_HEADERS),
            evidence.copy(status = Http3ProbeStatus.UNSUPPORTED),
            evidence.copy(status = Http3ProbeStatus.INVALID),
        ).forEach { assertNull(it.validatedHttp3ProbeEvidence()) }
        assertNotNull(
            evidence
                .copy(status = Http3ProbeStatus.NOT_OBSERVED, reason = "network_scope_unverified")
                .validatedHttp3ProbeEvidence(),
        )
    }

    @Test
    fun `HTTP error and incomplete body preserve protocol confirmation independently`() {
        val error = evidence.copy(status = Http3ProbeStatus.HTTP_ERROR, httpStatus = 403)
        assertNotNull(error.validatedHttp3ProbeEvidence())
        assertNull(error.copy(status = Http3ProbeStatus.COMPLETE).validatedHttp3ProbeEvidence())
        val partial = evidence.copy(status = Http3ProbeStatus.TIMEOUT, reason = "timeout", bodyComplete = false)
        assertEquals(true, parseHttp3ProbeEvidence(encode(partial))?.http3Validated)
        val noBody = evidence.copy(httpStatus = 204, responseBytes = 0, firstByteElapsedMs = null)
        assertNotNull(noBody.validatedHttp3ProbeEvidence())
    }

    @Test
    fun `redacted nested evidence keeps facts and removes peer and unknown data`() {
        val raw = encode(evidence).dropLast(1) + ",\"rawBody\":\"private-body-canary\"}"
        val entity =
            ProbeResultEntity(
                "result",
                "session",
                "http3",
                "www.cloudflare.com",
                "http3_complete",
                Json.encodeToString(
                    ListSerializer(ProbeDetail.serializer()),
                    listOf(ProbeDetail("http3Evidence", raw)),
                ),
                1,
            )
        val redacted = DiagnosticsArchiveRedactor(Json).redact(entity)
        assertFalse(redacted.detailJson.contains("private-body-canary"))
        assertFalse(redacted.detailJson.contains("192.0.2.1"))
        val details = Json.decodeFromString(ListSerializer(ProbeDetail.serializer()), redacted.detailJson)
        val parsed = requireNotNull(parseHttp3ProbeEvidence(details))
        assertNull(parsed.peerAddress)
        assertEquals(true, parsed.http3Validated)
        assertEquals(64L, parsed.responseBytes)
    }

    @Test
    fun `true HTTP3 scale never invents TCP and keeps body timeout separate`() {
        val partial = evidence.copy(status = Http3ProbeStatus.TIMEOUT, reason = "timeout", bodyComplete = false)
        val lane =
            requireNotNull(
                ProbeResult("http3", "control", "http3_incomplete", listOf(detail(partial)))
                    .toConnectionStageScale(),
            ).lanes.single()
        assertEquals(ConnectionLaneKind.HTTP3, lane.kind)
        assertEquals(ConnectionStageState.NOT_APPLICABLE, lane.stages.single { it.stage == ConnectionStage.TCP }.state)
        assertEquals(
            ConnectionStageState.SUCCEEDED,
            lane.stages.single { it.stage == ConnectionStage.QUIC_HANDSHAKE }.state,
        )
        assertEquals(
            ConnectionStageState.SUCCEEDED,
            lane.stages.single { it.stage == ConnectionStage.HTTP_HEADERS }.state,
        )
        assertEquals(ConnectionStageState.TIMED_OUT, lane.stages.single { it.stage == ConnectionStage.BODY }.state)
    }

    private fun encode(value: Http3ProbeEvidence) = Json.encodeToString(Http3ProbeEvidence.serializer(), value)

    private fun detail(value: Http3ProbeEvidence) = ProbeDetail("http3Evidence", encode(value))
}
