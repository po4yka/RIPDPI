package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStageTransferTest {
    @Test
    fun `cancelled body preserves progress first byte and elapsed timing`() {
        val lane = progress("cancelled").toConnectionStageScale().lanes.single()
        assertEquals(ConnectionStageState.CANCELLED, lane.at(ConnectionStage.BODY).state)
        assertEquals(128L, lane.at(ConnectionStage.BODY).byteCount)
        assertEquals(200L, lane.at(ConnectionStage.BODY).elapsedMs)
        assertEquals(50L, lane.at(ConnectionStage.FIRST_BODY_BYTE).elapsedMs)
        assertNull(lane.at(ConnectionStage.FIRST_BODY_BYTE).durationMs)
        assertEquals(ConnectionStageState.OBSERVED, lane.at(ConnectionStage.HTTP_HEADERS).state)
    }

    @Test
    fun `initial live envelope does not claim that response body has started`() {
        listOf(null, "cancelled", "deadline", "idle_timeout").forEach { reason ->
            val progress = TransferProgress("private", TransferMeasurement(1, 1, 0, 0, terminationReason = reason))
            val lane = progress.toConnectionStageScale().lanes.single()
            assertEquals(ConnectionStageState.UNKNOWN, lane.at(ConnectionStage.BODY).state)
            assertEquals(ConnectionStageState.UNKNOWN, lane.at(ConnectionStage.HTTP_HEADERS).state)
        }
    }

    @Test
    fun `live body and response completion use separate states`() {
        assertEquals(
            ConnectionStageState.RUNNING,
            progress(null)
                .toConnectionStageScale()
                .lanes
                .single()
                .at(ConnectionStage.BODY)
                .state,
        )
        val complete =
            progress("content_length_complete").let {
                it.copy(measurement = it.measurement.copy(expectedBodyByteCount = 128, responseComplete = true))
            }
        assertEquals(
            ConnectionStageState.SUCCEEDED,
            complete
                .toConnectionStageScale()
                .lanes
                .single()
                .at(ConnectionStage.BODY)
                .state,
        )
        val window = progress("window_limit").let { it.copy(measurement = it.measurement.copy(windowComplete = true)) }
        assertEquals(
            ConnectionStageState.PARTIAL,
            window
                .toConnectionStageScale()
                .lanes
                .single()
                .at(ConnectionStage.BODY)
                .state,
        )
    }

    @Test
    fun `cancelled setup marks measured failure stage and never starts body`() {
        for ((reason, state) in listOf(
            "cancelled" to ConnectionStageState.CANCELLED,
            "deadline" to ConnectionStageState.TIMED_OUT,
        )) {
            val run = TransferMeasurement(1, 1, 0, 100, terminationReason = reason)
            val result = transfer(listOf(run), "tcp_connect", "none", "none", "none")
            val lane = requireNotNull(result.toConnectionStageScale()).lanes.single()
            assertEquals(state, lane.at(ConnectionStage.TCP).state)
            assertEquals(ConnectionStageState.NOT_REACHED, lane.at(ConnectionStage.BODY).state)
            assertEquals(ConnectionStageState.UNKNOWN, lane.at(ConnectionStage.DNS).state)
        }
    }

    @Test
    fun `timings align by run and mismatched arrays never cross attempts`() {
        val run = progress("idle_timeout").measurement.copy(runCount = 2)
        val second = run.copy(runIndex = 2)
        val result = transfer(listOf(run, second), "body_read|body_read", "3|4", "5|6", "200|206")
        val lanes = requireNotNull(result.toConnectionStageScale()).lanes
        assertEquals(listOf(1, 2), lanes.map { it.attempt })
        assertEquals(listOf(5L, 6L), lanes.map { it.at(ConnectionStage.TLS).durationMs })
        assertTrue(lanes.all { it.at(ConnectionStage.TCP).durationMs == null })
        val bad =
            result.copy(
                details =
                    result.details.filterNot { it.key == "tlsHandshakeMsReadings" } +
                        ProbeDetail("tlsHandshakeMsReadings", "5"),
            )
        val invalid = requireNotNull(bad.toConnectionStageScale()).lanes
        assertTrue(invalid.all { it.at(ConnectionStage.TLS).durationMs == null })
        assertTrue(invalid.all { it.at(ConnectionStage.TCP).state == ConnectionStageState.UNKNOWN })
    }

    @Test
    fun `scope warning and numeric bounds survive conservative projection`() {
        val result = transfer(listOf(progress("idle_timeout").measurement), "body_read", "1", "2", "200")
        val scoped = result.copy(details = result.details + ProbeDetail("transferNetworkScope", "unverified"))
        assertTrue(requireNotNull(scoped.toConnectionStageScale()).lanes.single().networkScopeUnverified)
        val invalid = progress(null).let { it.copy(measurement = it.measurement.copy(receivedBodyByteCount = -1)) }
        assertEquals(
            ConnectionStageState.UNKNOWN,
            invalid
                .toConnectionStageScale()
                .lanes
                .single()
                .at(ConnectionStage.BODY)
                .state,
        )
    }

    @Test
    fun `duplicated transfer envelope cannot fall back to less strict readings`() {
        val result = transfer(listOf(progress("idle_timeout").measurement), "body_read", "1", "2", "200")
        val duplicate = result.copy(details = result.details + result.details.first())
        assertTrue(
            requireNotNull(duplicate.toConnectionStageScale()).lanes.single().stages.all {
                it.state ==
                    ConnectionStageState.UNKNOWN
            },
        )
    }

    @Test
    fun `plain HTTP zero TLS sentinel never becomes handshake success`() {
        val result = transfer(listOf(progress("idle_timeout").measurement), "body_read", "1", "0", "200")
        for ((url, expected) in listOf(
            "http://private/" to ConnectionStageState.NOT_APPLICABLE,
            "https://private/" to ConnectionStageState.SUCCEEDED,
            "unknown" to ConnectionStageState.UNKNOWN,
        )) {
            val source = result.copy(details = result.details + ProbeDetail("url", url))
            assertEquals(
                expected,
                requireNotNull(source.toConnectionStageScale())
                    .lanes
                    .single()
                    .at(ConnectionStage.TLS)
                    .state,
            )
        }
    }

    private fun progress(reason: String?) =
        TransferProgress(
            "private",
            TransferMeasurement(
                1,
                1,
                128,
                200,
                firstBodyByteMs = 50,
                lastBodyProgressMs = 150,
                terminationReason = reason,
                samples = listOf(TransferProgressSample(150, 128)),
            ),
        )

    private fun transfer(
        runs: List<TransferMeasurement>,
        failure: String,
        tcp: String,
        tls: String,
        status: String,
    ) = ProbeResult(
        "throughput_window",
        "private",
        "throughput_failed",
        listOf(
            ProbeDetail(
                "transferEvidence",
                Json.encodeToString(TransferEvidence.serializer(), TransferEvidence(runs = runs)),
            ),
            ProbeDetail("failureStageReadings", failure),
            ProbeDetail("tcpConnectMsReadings", tcp),
            ProbeDetail("tlsHandshakeMsReadings", tls),
            ProbeDetail("httpStatusCodeReadings", status),
        ),
    )

    private fun ConnectionStageLane.at(stage: ConnectionStage) = stages.single { it.stage == stage }
}
