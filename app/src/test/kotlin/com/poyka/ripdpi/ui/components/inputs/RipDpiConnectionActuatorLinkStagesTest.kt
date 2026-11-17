package com.poyka.ripdpi.ui.components.inputs

import com.poyka.ripdpi.ui.components.signal.RipDpiLinkStage
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStage
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStageState
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStageUiState
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStatus
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorUiState
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Test

class RipDpiConnectionActuatorLinkStagesTest {
    private fun state(
        status: StatusConnectionActuatorStatus,
        vararg stages: StatusConnectionActuatorStageState,
    ): StatusConnectionActuatorUiState {
        val base = StatusConnectionActuatorUiState(status = status)
        if (stages.isEmpty()) return base
        return base.copy(
            stages =
                StatusConnectionActuatorStage.entries
                    .mapIndexed { index, stage -> StatusConnectionActuatorStageUiState(stage, "", stages[index]) }
                    .toImmutableList(),
        )
    }

    private fun all(stage: RipDpiLinkStage) = List(StatusConnectionActuatorStage.entries.size) { stage }

    @Test
    fun `a state with no stage detail shows what its status means`() {
        assertEquals(all(RipDpiLinkStage.Pending), state(StatusConnectionActuatorStatus.Open).linkStages(false))
        assertEquals(all(RipDpiLinkStage.Active), state(StatusConnectionActuatorStatus.Engaging).linkStages(false))
        assertEquals(all(RipDpiLinkStage.Complete), state(StatusConnectionActuatorStatus.Locked).linkStages(false))
        assertEquals(all(RipDpiLinkStage.Complete), state(StatusConnectionActuatorStatus.Degraded).linkStages(false))
        // A fault that names no stage lights no lamp: the tile does not blame a stage on its own.
        assertEquals(all(RipDpiLinkStage.Pending), state(StatusConnectionActuatorStatus.Fault).linkStages(false))
    }

    @Test
    fun `a stage that the state names keeps its own lamp`() {
        val pending = StatusConnectionActuatorStageState.Pending
        val complete = StatusConnectionActuatorStageState.Complete
        val fault =
            state(
                StatusConnectionActuatorStatus.Fault,
                pending,
                pending,
                pending,
                StatusConnectionActuatorStageState.Failed,
                pending,
            )
        val degraded =
            state(
                StatusConnectionActuatorStatus.Degraded,
                complete,
                StatusConnectionActuatorStageState.Warning,
                complete,
                complete,
                complete,
            )

        assertEquals(
            listOf(
                RipDpiLinkStage.Pending,
                RipDpiLinkStage.Pending,
                RipDpiLinkStage.Pending,
                RipDpiLinkStage.Failed,
                RipDpiLinkStage.Pending,
            ),
            fault.linkStages(false),
        )
        assertEquals(RipDpiLinkStage.Warning, degraded.linkStages(false)[1])
        assertEquals(4, degraded.linkStages(false).count { it == RipDpiLinkStage.Complete })
    }

    @Test
    fun `a paused session holds every lamp`() {
        assertEquals(all(RipDpiLinkStage.Held), state(StatusConnectionActuatorStatus.Locked).linkStages(true))
    }
}
