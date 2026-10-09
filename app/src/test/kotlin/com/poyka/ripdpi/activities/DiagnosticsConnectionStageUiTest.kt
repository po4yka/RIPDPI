package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.ConnectionLaneKind
import com.poyka.ripdpi.diagnostics.ConnectionStage
import com.poyka.ripdpi.diagnostics.ConnectionStageLane
import com.poyka.ripdpi.diagnostics.ConnectionStageMeasurement
import com.poyka.ripdpi.diagnostics.ConnectionStageScale
import com.poyka.ripdpi.diagnostics.ConnectionStageState
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.TransferMeasurement
import com.poyka.ripdpi.diagnostics.TransferProgress
import com.poyka.ripdpi.diagnostics.TransferProgressSample
import com.poyka.ripdpi.diagnostics.toConnectionStageScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsConnectionStageUiTest {
    @Test
    fun `independent lanes retain attempts states and distinct time meanings`() {
        val stages =
            listOf(
                ConnectionStageMeasurement(ConnectionStage.TCP, ConnectionStageState.UNKNOWN),
                ConnectionStageMeasurement(ConnectionStage.TLS, ConnectionStageState.SUCCEEDED, durationMs = 20),
                ConnectionStageMeasurement(
                    ConnectionStage.FIRST_BODY_BYTE,
                    ConnectionStageState.OBSERVED,
                    elapsedMs = 40,
                ),
                ConnectionStageMeasurement(ConnectionStage.BODY, ConnectionStageState.PARTIAL, byteCount = 128),
            )
        val ui =
            ConnectionStageScale(
                listOf(
                    ConnectionStageLane(ConnectionLaneKind.TRANSFER, 1, stages),
                    ConnectionStageLane(ConnectionLaneKind.TRANSFER, 2, stages, networkScopeUnverified = true),
                ),
            ).toConnectionStageUiModel()
        assertEquals(listOf(1, 2), ui.lanes.map { it.attempt })
        assertEquals(
            stages.map { it.state },
            ui.lanes
                .first()
                .stages
                .map { it.state },
        )
        assertNull(
            ui.lanes
                .first()
                .stages
                .first()
                .durationMs,
        )
        assertEquals(
            20L,
            ui.lanes
                .first()
                .stages[1]
                .durationMs,
        )
        assertNull(
            ui.lanes
                .first()
                .stages[1]
                .elapsedMs,
        )
        assertEquals(
            40L,
            ui.lanes
                .first()
                .stages[2]
                .elapsedMs,
        )
        assertNull(
            ui.lanes
                .first()
                .stages[2]
                .durationMs,
        )
        assertFalse(ui.lanes.first().networkScopeUnverified)
        assertTrue(ui.lanes.last().networkScopeUnverified)
    }

    @Test
    fun `report scope warning reaches every projected lane without altering evidence`() {
        val original =
            ConnectionStageScale(
                listOf(
                    ConnectionStageLane(
                        ConnectionLaneKind.QUIC,
                        stages =
                            listOf(
                                ConnectionStageMeasurement(
                                    ConnectionStage.QUIC_RESPONSE,
                                    ConnectionStageState.OBSERVED,
                                ),
                            ),
                    ),
                ),
            )
        val ui = original.toConnectionStageUiModel(networkScopeUnverified = true)
        assertTrue(ui.lanes.single().networkScopeUnverified)
        assertEquals(
            ConnectionStageState.OBSERVED,
            ui.lanes
                .single()
                .stages
                .single()
                .state,
        )
    }

    @Test
    fun `result row uses the shared projector and carries report scope warning`() {
        val result =
            ProbeResult(
                "quic_reachability",
                "example.org",
                "reachable",
                listOf(ProbeDetail("status", "quic_response")),
            )
        val expected = result.toConnectionStageScale()!!.toConnectionStageUiModel(networkScopeUnverified = true)
        val row =
            testDiagnosticsUiCoreSupport().toProbeResultUiModel(
                index = 0,
                pathMode = ScanPathMode.RAW_PATH,
                result = result,
                networkScopeUnverified = true,
            )
        assertEquals(expected, row.connectionStages)
        assertTrue(
            row.connectionStages!!
                .lanes
                .single()
                .networkScopeUnverified,
        )
        assertNull(
            testDiagnosticsUiCoreSupport()
                .toProbeResultUiModel(
                    1,
                    ScanPathMode.RAW_PATH,
                    result.copy(probeType = "unsupported"),
                ).connectionStages,
        )
    }

    @Test
    fun `live transfer uses the shared evidence projector`() {
        val progress =
            TransferProgress(
                "example.org",
                TransferMeasurement(
                    runIndex = 1,
                    runCount = 1,
                    receivedBodyByteCount = 128,
                    elapsedMs = 30,
                    firstBodyByteMs = 10,
                    lastBodyProgressMs = 20,
                    samples = listOf(TransferProgressSample(20, 128)),
                ),
            )
        val expected = progress.toConnectionStageScale().toConnectionStageUiModel()
        assertEquals(expected, progress.toTransferUiModel().connectionStages)
        assertTrue(expected.lanes.isNotEmpty())
        assertEquals(
            128L,
            expected.lanes
                .single()
                .stages
                .single { it.stage == ConnectionStage.BODY }
                .byteCount,
        )
    }
}
