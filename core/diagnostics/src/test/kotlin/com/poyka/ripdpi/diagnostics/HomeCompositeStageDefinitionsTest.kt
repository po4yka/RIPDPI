package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCompositeStageDefinitionsTest {
    @Test
    fun `terminal sibling update preserves a running stage as progress owner`() {
        val stages =
            listOf(
                stage("one", "session-one", DiagnosticsHomeCompositeStageStatus.COMPLETED),
                stage("two", "session-two", DiagnosticsHomeCompositeStageStatus.RUNNING),
            )

        assertEquals(1, stages.activeStageIndexAfterUpdate(previousIndex = 0, updatedIndex = 0))
    }

    @Test
    fun `running update becomes progress owner`() {
        val stages =
            listOf(
                stage("one", "session-one", DiagnosticsHomeCompositeStageStatus.RUNNING),
                stage("two", "session-two", DiagnosticsHomeCompositeStageStatus.RUNNING),
            )

        assertEquals(1, stages.activeStageIndexAfterUpdate(previousIndex = 0, updatedIndex = 1))
    }

    private fun stage(
        key: String,
        sessionId: String,
        status: DiagnosticsHomeCompositeStageStatus,
    ) = DiagnosticsHomeCompositeStageSummary(
        stageKey = key,
        stageLabel = key,
        profileId = key,
        pathMode = ScanPathMode.RAW_PATH,
        sessionId = sessionId,
        status = status,
        headline = key,
        summary = key,
    )
}
