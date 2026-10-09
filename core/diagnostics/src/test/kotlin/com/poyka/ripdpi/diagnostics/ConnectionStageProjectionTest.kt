package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStageProjectionTest {
    @Test
    fun `HTTP status proves received headers without claiming transport failure`() {
        val result =
            ProbeResult(
                "strategy_http",
                "private.example",
                "http_client_error",
                listOf(ProbeDetail("httpStatusCode", "403")),
            )
        val stage =
            result.toConnectionStageScale()?.lanes?.single()?.stages?.single {
                it.stage ==
                    ConnectionStage.HTTP_HEADERS
            }
        assertEquals(ConnectionStageState.OBSERVED, stage?.state)
    }

    @Test
    fun `TLS versions and plaintext HTTP remain independent`() {
        val result =
            probe(
                "domain_reachability",
                "tls13Status" to "tls_ok",
                "tls12Status" to "tls_handshake_failed",
                "httpStatus" to "http_error",
            )
        val lanes = requireNotNull(result.toConnectionStageScale()).lanes
        assertEquals(
            listOf(
                ConnectionLaneKind.TLS13,
                ConnectionLaneKind.TLS12,
                ConnectionLaneKind.TLS_ECH,
                ConnectionLaneKind.HTTP,
            ),
            lanes.map {
                it.kind
            },
        )
        assertEquals(ConnectionStageState.SUCCEEDED, lanes[0].at(ConnectionStage.TLS).state)
        assertEquals(ConnectionStageState.UNKNOWN, lanes[0].at(ConnectionStage.TCP).state)
        assertEquals(ConnectionStageState.UNKNOWN, lanes[1].at(ConnectionStage.TLS).state)
        assertEquals(ConnectionStageState.FAILED, lanes[3].at(ConnectionStage.HTTP_HEADERS).state)
        assertEquals(ConnectionStageState.NOT_APPLICABLE, lanes[3].at(ConnectionStage.TLS).state)
    }

    @Test
    fun `selected TLS duration is not attributed to all independent profiles`() {
        val lanes =
            requireNotNull(
                probe(
                    "strategy_https",
                    "tls13Status" to "tls_ok",
                    "tls12Status" to "tls_ok",
                    "tcpConnectMs" to "10",
                    "tlsHandshakeMs" to "20",
                ).toConnectionStageScale(),
            ).lanes
        assertTrue(lanes.all { it.stages.all { stage -> stage.durationMs == null } })
    }

    @Test
    fun `explicit proxy failure keeps unmeasured earlier stages unknown`() {
        val lane =
            requireNotNull(
                probe(
                    "strategy_https",
                    "tls13Status" to "tls_handshake_failed",
                    "tls13FailureStage" to "socks5_negotiation",
                    "tls13FailureDurationMs" to "19",
                ).toConnectionStageScale(),
            ).lanes.first()
        assertEquals(ConnectionStageState.FAILED, lane.at(ConnectionStage.PROXY).state)
        assertEquals(19L, lane.at(ConnectionStage.PROXY).elapsedMs)
        assertEquals(ConnectionStageState.UNKNOWN, lane.at(ConnectionStage.TCP).state)
        assertEquals(ConnectionStageState.NOT_REACHED, lane.at(ConnectionStage.TLS).state)
    }

    @Test
    fun `QUIC response never proves full TLS or HTTP3`() {
        val lane =
            requireNotNull(
                probe(
                    "strategy_quic",
                    "status" to "quic_initial_response",
                    "latencyMs" to "42",
                    "http3Validated" to "false",
                ).toConnectionStageScale(),
            ).lanes.single()
        assertEquals(listOf(ConnectionStage.QUIC_RESPONSE), lane.stages.map { it.stage })
        assertEquals(ConnectionStageState.OBSERVED, lane.stages.single().state)
        assertEquals(42L, lane.stages.single().durationMs)
    }

    @Test
    fun `pinned matrix skips DNS and exposes HTTP403 as received headers`() {
        val lane =
            requireNotNull(
                probe(
                    "selective_availability",
                    "dnsStatus" to "admitted_addresses",
                    "dnsElapsedMs" to "0",
                    "httpStatus" to "failed",
                    "httpStatusCode" to "403",
                    "attempt" to "2",
                ).toConnectionStageScale(),
            ).lanes.single()
        assertEquals(ConnectionStageState.NOT_APPLICABLE, lane.at(ConnectionStage.DNS).state)
        assertNull(lane.at(ConnectionStage.DNS).durationMs)
        assertEquals(ConnectionStageState.OBSERVED, lane.at(ConnectionStage.HTTP_HEADERS).state)
        assertEquals(403, lane.at(ConnectionStage.HTTP_HEADERS).httpStatusCode)
        assertEquals(2, lane.attempt)
    }

    @Test
    fun `DNS zero answers alone is inconclusive`() {
        val lanes =
            requireNotNull(
                probe(
                    "dns_integrity",
                    "udpAnswerSetSize" to "0",
                    "encryptedAnswerSetSize" to "0",
                ).toConnectionStageScale(),
            ).lanes
        assertTrue(lanes.all { it.at(ConnectionStage.DNS).state == ConnectionStageState.UNKNOWN })
    }

    @Test
    fun `DNS successful empty response and explicit timeout are distinct`() {
        val success =
            requireNotNull(
                probe(
                    "dns_integrity",
                    "udpSuccessCount" to "1",
                    "udpAttemptCount" to "2",
                    "udpAnswerSetSize" to "0",
                ).toConnectionStageScale(),
            ).lanes.first()
        val timeout =
            requireNotNull(
                probe("dns_integrity", "udpSuccessCount" to "0", "udpErrorKind" to "timeout").toConnectionStageScale(),
            ).lanes.first()
        assertEquals(ConnectionStageState.SUCCEEDED, success.at(ConnectionStage.DNS).state)
        assertEquals(ConnectionStageState.TIMED_OUT, timeout.at(ConnectionStage.DNS).state)
    }

    @Test
    fun `raw resolved text and TCP responses are never HTTP evidence`() {
        val domain = requireNotNull(probe("domain_reachability", "resolved" to "redacted").toConnectionStageScale())
        assertTrue(domain.lanes.all { it.at(ConnectionStage.DNS).state == ConnectionStageState.UNKNOWN })
        val tcp =
            requireNotNull(
                probe(
                    "tcp_fat_header",
                    "responsesSeen" to "2",
                    "bytesSent" to "1024",
                    "synAckLatencyMs" to "10",
                ).toConnectionStageScale(),
            ).lanes.single()
        assertEquals(ConnectionStageState.SUCCEEDED, tcp.at(ConnectionStage.TCP).state)
        assertNull(tcp.at(ConnectionStage.TCP).durationMs)
        assertNull(tcp.at(ConnectionStage.TCP).byteCount)
        assertEquals(ConnectionStageState.UNKNOWN, tcp.at(ConnectionStage.HTTP_HEADERS).state)
    }

    @Test
    fun `service and circumvention keep separate branches`() {
        val service =
            requireNotNull(
                probe(
                    "service_reachability",
                    "bootstrapStatus" to "http_ok",
                    "mediaStatus" to "http_error",
                    "gatewayStatus" to "tcp_connect_ok",
                    "quicStatus" to "not_run",
                ).toConnectionStageScale(),
            ).lanes
        assertEquals(4, service.size)
        assertEquals(ConnectionStageState.OBSERVED, service[0].at(ConnectionStage.HTTP_HEADERS).state)
        assertEquals(ConnectionStageState.FAILED, service[1].at(ConnectionStage.HTTP_HEADERS).state)
        val circumvention =
            requireNotNull(
                probe(
                    "circumvention_reachability",
                    "bootstrapStatus" to "http_status_403",
                    "handshakeStatus" to "tls_ok",
                ).toConnectionStageScale(),
            ).lanes
        assertEquals(2, circumvention.size)
        assertEquals(ConnectionStageState.SUCCEEDED, circumvention[1].at(ConnectionStage.TLS).state)
        assertEquals(ConnectionStageState.UNKNOWN, circumvention[1].at(ConnectionStage.TCP).state)
    }

    @Test
    fun `duplicate and malformed numeric fields are discarded`() {
        val lane =
            requireNotNull(
                probe(
                    "selective_availability",
                    "tcpStatus" to "ok",
                    "tcpStatus" to "ok",
                    "bodyByteCount" to "-1",
                    "dnsElapsedMs" to "9223372036854775808",
                    "httpStatusCode" to "999",
                    "attempt" to "999",
                ).toConnectionStageScale(),
            ).lanes.single()
        assertEquals(ConnectionStageState.UNKNOWN, lane.at(ConnectionStage.TCP).state)
        assertNull(lane.at(ConnectionStage.BODY).byteCount)
        assertNull(lane.at(ConnectionStage.DNS).durationMs)
        assertNull(lane.at(ConnectionStage.HTTP_HEADERS).httpStatusCode)
        assertNull(lane.attempt)
    }

    @Test
    fun `unsupported probe and known probe without details remain conservative`() {
        assertNull(probe("unknown_probe").toConnectionStageScale())
        val scale = requireNotNull(probe("strategy_https").toConnectionStageScale())
        assertTrue(scale.lanes.all { it.at(ConnectionStage.TLS).state == ConnectionStageState.UNKNOWN })
    }

    @Test
    fun `unscoped HTTP status cannot contaminate independent service branches`() {
        val lanes =
            requireNotNull(
                probe("service_reachability", "httpStatusCode" to "200").toConnectionStageScale(),
            ).lanes
        assertEquals(ConnectionStageState.UNKNOWN, lanes[0].at(ConnectionStage.HTTP_HEADERS).state)
        assertEquals(ConnectionStageState.UNKNOWN, lanes[1].at(ConnectionStage.HTTP_HEADERS).state)
    }

    private fun probe(
        type: String,
        vararg fields: Pair<String, String>,
    ) = ProbeResult(type, "private.example", "unclassified", fields.map { ProbeDetail(it.first, it.second) })

    private fun ConnectionStageLane.at(stage: ConnectionStage) = stages.single { it.stage == stage }
}
