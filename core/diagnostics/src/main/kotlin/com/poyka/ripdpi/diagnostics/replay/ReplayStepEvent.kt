package com.poyka.ripdpi.diagnostics.replay

/**
 * Streaming event from [ProbeReplayService.run]. Each step transitions
 * Pending -> Success | Failure exactly once. The terminal event is
 * always [Finished], regardless of which step caused termination.
 *
 * The orchestrator emits events in step order; consumers should
 * accumulate into a Map<ReplayStepKind, ReplayStepStatus> keyed by kind.
 */
sealed class ReplayStepEvent {
    /** The step started its work. `timestampMs` is wall-clock at emit. */
    data class StepStarted(
        val step: ReplayStepKind,
        val timestampMs: Long,
    ) : ReplayStepEvent()

    /** The step completed successfully. `durationMs` is the elapsed wall-clock since [StepStarted]. */
    data class StepCompleted(
        val step: ReplayStepKind,
        val durationMs: Long,
        val detail: String,
    ) : ReplayStepEvent()

    /**
     * The step failed. `errorKind` classifies the cause for downstream
     * recommendation lookup. `detail` is a localisable, non-PII string
     * (NEVER include the resolved IP - use the original domain only).
     */
    data class StepFailed(
        val step: ReplayStepKind,
        val errorKind: ReplayErrorKind,
        val detail: String,
    ) : ReplayStepEvent()

    /**
     * Terminal event. [verdict] is Success if every step completed, Failure
     * if any step failed (terminalStep carries which one), Cancelled if the
     * flow was cancelled before completion.
     *
     * [recommendationKey] is a string-resource key resolvable to a
     * localised template - empty for the generic-fallback case so the
     * UI can decide between a default message and silence.
     */
    data class Finished(
        val verdict: ReplayVerdict,
        val terminalStep: ReplayStepKind?,
        val recommendationKey: String,
    ) : ReplayStepEvent()
}
