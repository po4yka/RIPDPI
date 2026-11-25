package com.poyka.ripdpi.diagnostics.replay

sealed class ReplayStepEvent {
    data class StepStarted(
        val step: ReplayStepKind,
        val timestampMs: Long,
    ) : ReplayStepEvent()

    data class StepCompleted(
        val step: ReplayStepKind,
        val durationMs: Long,
        val detail: String,
    ) : ReplayStepEvent()

    data class StepFailed(
        val step: ReplayStepKind,
        val errorKind: ReplayErrorKind,
        val detail: String,
    ) : ReplayStepEvent()

    data class Finished(
        val verdict: ReplayVerdict,
        val terminalStep: ReplayStepKind?,
        val recommendationKey: String,
    ) : ReplayStepEvent()
}
