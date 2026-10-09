package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStageTlsAttributionTest {
    @Test
    fun `aggregate domain failure cannot be assigned to a TLS profile`() {
        val result =
            ProbeResult(
                "domain_reachability",
                "private",
                "unreachable",
                listOf(
                    ProbeDetail("tls13Status", "tls_handshake_failed"),
                    ProbeDetail("tls12Status", "tls_handshake_failed"),
                    ProbeDetail("tlsEchStatus", "tls_handshake_failed"),
                    ProbeDetail("tlsFailureStage", "tcp_connect"),
                ),
            )
        val lanes = requireNotNull(result.toConnectionStageScale()).lanes.take(3)
        for (lane in lanes) {
            assertEquals(ConnectionStageState.UNKNOWN, lane.stages.single { it.stage == ConnectionStage.TLS }.state)
            assertEquals(ConnectionStageState.UNKNOWN, lane.stages.single { it.stage == ConnectionStage.TCP }.state)
        }
    }

    @Test
    fun `exact TLS failure stage and certificate failure remain attributed`() {
        val result =
            ProbeResult(
                "strategy_https",
                "private",
                "unreachable",
                listOf(
                    ProbeDetail("tls13Status", "tls_handshake_failed"),
                    ProbeDetail("tls13FailureStage", "tls_handshake"),
                    ProbeDetail("tls12Status", "tls_cert_invalid"),
                ),
            )
        for (lane in requireNotNull(result.toConnectionStageScale()).lanes.take(2)) {
            assertEquals(ConnectionStageState.FAILED, lane.stages.single { it.stage == ConnectionStage.TLS }.state)
        }
    }

    @Test
    fun `endpoint generic TLS failure cannot identify a stage`() {
        for ((type, key, kind) in listOf(
            Triple("service_reachability", "gatewayStatus", ConnectionLaneKind.GATEWAY),
            Triple("circumvention_reachability", "handshakeStatus", ConnectionLaneKind.HANDSHAKE),
        )) {
            val result = ProbeResult(type, "private", "unreachable", listOf(ProbeDetail(key, "tls_handshake_failed")))
            val lane = requireNotNull(result.toConnectionStageScale()).lanes.single { it.kind == kind }
            assertEquals(ConnectionStageState.UNKNOWN, lane.stages.single { it.stage == ConnectionStage.TLS }.state)
            assertEquals(ConnectionStageState.UNKNOWN, lane.stages.single { it.stage == ConnectionStage.TCP }.state)
        }
    }

    @Test
    fun `endpoint connection error can come from resolver or proxy`() {
        val result =
            ProbeResult(
                "service_reachability",
                "private",
                "unreachable",
                listOf(ProbeDetail("gatewayStatus", "tcp_connect_failed")),
            )
        val lane =
            requireNotNull(
                result.toConnectionStageScale(),
            ).lanes.single { it.kind == ConnectionLaneKind.GATEWAY }
        assertEquals(ConnectionStageState.UNKNOWN, lane.stages.single { it.stage == ConnectionStage.TCP }.state)
        assertEquals(ConnectionStageState.NOT_APPLICABLE, lane.stages.single { it.stage == ConnectionStage.TLS }.state)
    }
}
