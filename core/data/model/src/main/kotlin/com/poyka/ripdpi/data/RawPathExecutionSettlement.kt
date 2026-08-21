package com.poyka.ripdpi.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RawPathExecutionResult(
    @SerialName("settlement") val settlement: RawPathExecutionSettlement,
    @SerialName("execution_outcome") val executionOutcome: RawPathExecutionOutcome,
    @SerialName("execution_failure") val executionFailure: String? = null,
)

class RawPathExecutionCancelledException(
    val result: RawPathExecutionResult,
    cause: CancellationException,
) : CancellationException(cause.message ?: cause::class.java.simpleName) {
    init {
        initCause(cause)
    }
}

@Serializable
enum class RawPathExecutionOutcome {
    @SerialName("completed")
    Completed,

    @SerialName("block_failed")
    BlockFailed,

    @SerialName("block_cancelled")
    BlockCancelled,

    @SerialName("entry_failed")
    EntryFailed,

    @SerialName("entry_cancelled")
    EntryCancelled,
}

@Serializable
data class RawPathExecutionSettlement(
    @SerialName("raw_window_generation") val rawWindowGeneration: Long,
    @SerialName("resume_intent_generation") val resumeIntentGeneration: Long,
    @SerialName("outcome") val outcome: RawPathExecutionSettlementOutcome,
    @SerialName("runtime_was_running") val runtimeWasRunning: Boolean,
    @SerialName("resume_required") val resumeRequired: Boolean,
    @SerialName("post_runtime_context") val postRuntimeContext: RawPathRuntimeContext,
    @SerialName("restore_failure") val restoreFailure: String? = null,
)

@Serializable
enum class RawPathExecutionSettlementOutcome {
    @SerialName("restored")
    Restored,

    @SerialName("restore_not_required")
    RestoreNotRequired,

    @SerialName("restore_failed")
    RestoreFailed,

    @SerialName("restore_policy_disabled")
    RestorePolicyDisabled,

    @SerialName("superseded_by_user_stop")
    SupersededByUserStop,
}

@Serializable
data class RawPathRuntimeContext(
    @SerialName("status") val status: RawPathRuntimeStatus? = null,
    @SerialName("mode") val mode: Mode? = null,
)

@Serializable
enum class RawPathRuntimeStatus {
    @SerialName("halted")
    Halted,

    @SerialName("reconnecting")
    Reconnecting,

    @SerialName("running")
    Running,
}

fun AppStatus.toRawPathRuntimeStatus(): RawPathRuntimeStatus =
    when (this) {
        AppStatus.Halted -> RawPathRuntimeStatus.Halted
        AppStatus.Reconnecting -> RawPathRuntimeStatus.Reconnecting
        AppStatus.Running -> RawPathRuntimeStatus.Running
    }
