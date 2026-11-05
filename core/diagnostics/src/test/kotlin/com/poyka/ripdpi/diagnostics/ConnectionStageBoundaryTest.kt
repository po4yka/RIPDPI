package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStageBoundaryTest {
    @Test
    fun `legacy throughput keeps independent aligned readings without transfer envelope`() {
        val result =
            ProbeResult(
                "throughput_window",
                "private",
                "throughput_failed",
                listOf(
                    ProbeDetail("runs", "2"),
                    ProbeDetail("statusReadings", "http_ok|http_error"),
                    ProbeDetail("failureStageReadings", "none|tcp_connect"),
                    ProbeDetail("tcpConnectMsReadings", "10|none"),
                    ProbeDetail("tlsHandshakeMsReadings", "20|none"),
                    ProbeDetail("httpStatusCodeReadings", "200|none"),
                    ProbeDetail("durationMsReadings", "40|30"),
                    ProbeDetail("readCompletionReadings", "window_complete|not_started"),
                ),
            )
        assertEquals(2, result.toConnectionStageScale()?.lanes?.size)
    }

    @Test
    fun `throughput completion readings distinguish a short body from truncation`() {
        val result =
            ProbeResult(
                "throughput_window",
                "private",
                "throughput_inconclusive",
                listOf(
                    ProbeDetail("runs", "2"),
                    ProbeDetail("statusReadings", "http_ok|http_ok"),
                    ProbeDetail("failureStageReadings", "none|body"),
                    ProbeDetail("tcpConnectMsReadings", "10|10"),
                    ProbeDetail("tlsHandshakeMsReadings", "20|20"),
                    ProbeDetail("httpStatusCodeReadings", "200|200"),
                    ProbeDetail("durationMsReadings", "40|40"),
                    ProbeDetail("readCompletionReadings", "body_complete_before_window|truncated_body"),
                ),
            )
        val bodyStates =
            requireNotNull(result.toConnectionStageScale()).lanes.map { lane ->
                lane.stages.single { it.stage == ConnectionStage.BODY }.state
            }
        assertEquals(listOf(ConnectionStageState.PARTIAL, ConnectionStageState.FAILED), bodyStates)
    }

    @Test
    fun `native capped and truncated matrix bodies remain partial`() {
        listOf("response_limit", "early_eof").forEach { reason ->
            val result =
                ProbeResult(
                    "selective_availability",
                    "private",
                    "matrix_target_body_incomplete",
                    listOf(
                        ProbeDetail("bodyStatus", "failed"),
                        ProbeDetail("failureStage", "body"),
                        ProbeDetail("bodyReason", reason),
                        ProbeDetail("bodyComplete", "false"),
                        ProbeDetail("bodyByteCount", "10"),
                    ),
                )
            val body =
                requireNotNull(result.toConnectionStageScale()).lanes.single().stages.single {
                    it.stage == ConnectionStage.BODY
                }
            assertEquals(ConnectionStageState.PARTIAL, body.state)
        }
    }

    @Test
    fun `contradictory matrix completion does not become success`() {
        val result =
            ProbeResult(
                "selective_availability",
                "private",
                "matrix_target_available",
                listOf(
                    ProbeDetail("bodyStatus", "ok"),
                    ProbeDetail("bodyComplete", "false"),
                    ProbeDetail("bodyByteCount", "10"),
                ),
            )
        val body =
            requireNotNull(result.toConnectionStageScale()).lanes.single().stages.single {
                it.stage ==
                    ConnectionStage.BODY
            }
        assertEquals(ConnectionStageState.UNKNOWN, body.state)
    }
}
