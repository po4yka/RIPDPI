package com.poyka.ripdpi.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Persisted command provenance. Unknown pre-schema-3 records intentionally have no origin. */
@Serializable
sealed interface RuntimeCommandOrigin {
    @Serializable
    @SerialName("user_start")
    data object UserStart : RuntimeCommandOrigin

    @Serializable
    @SerialName("user_stop")
    data object UserStop : RuntimeCommandOrigin

    @Serializable
    @SerialName("profile_mutation")
    data class ProfileMutation(
        val mutationOrigin: ProfileMutationOrigin,
        val mutationId: String,
    ) : RuntimeCommandOrigin {
        init {
            require(mutationId.isNotBlank())
        }
    }

    @Serializable
    @SerialName("measured_profile_activation")
    data class MeasuredActivation(
        val mutationId: String,
        val reference: ProfileUtilityReference,
    ) : RuntimeCommandOrigin {
        init {
            require(mutationId.isNotBlank())
        }
    }

    /** A failed ordinary Start may try its configured fallback without accepting a new user command. */
    @Serializable
    @SerialName("startup_fallback")
    data class StartupFallback(
        val predecessorCommandId: String,
    ) : RuntimeCommandOrigin {
        init {
            require(predecessorCommandId.isNotBlank())
        }
    }

    /** Checked process/sticky recovery of a recorded Running state; never authorizes Stopped. */
    @Serializable
    @SerialName("runtime_recovery")
    data class Recovery(
        val predecessorCommandId: String?,
        val priorAppliedIdentity: RuntimeAppliedUseIdentity?,
    ) : RuntimeCommandOrigin {
        init {
            require(priorAppliedIdentity == null || !predecessorCommandId.isNullOrBlank())
        }
    }

    /** Pause is not an activation command: only a checked matching resume may claim it. */
    @Serializable
    @SerialName("pause_resume")
    data class PauseResume(
        val token: String,
    ) : RuntimeCommandOrigin {
        init {
            require(token.isNotBlank())
        }
    }

    @Serializable
    @SerialName("reset_stop")
    data object ResetStop : RuntimeCommandOrigin

    @Serializable
    @SerialName("boot_policy")
    data object BootPolicy : RuntimeCommandOrigin
}

/** The durable claim binds ACK and termination to one attempt, not just its command generation. */
@Serializable
sealed interface RuntimeClaimKind {
    @Serializable
    @SerialName("activation")
    data object Activation : RuntimeClaimKind

    @Serializable
    @SerialName("resume")
    data class Resume(
        val token: String,
    ) : RuntimeClaimKind

    @Serializable
    @SerialName("recovery")
    data object Recovery : RuntimeClaimKind

    @Serializable
    @SerialName("continuation")
    data class Continuation(
        val predecessor: RuntimeAppliedUseIdentity,
    ) : RuntimeClaimKind
}

@Serializable
sealed interface RuntimeActivationPhase {
    @Serializable
    @SerialName("unbound")
    data object Unbound : RuntimeActivationPhase

    @Serializable
    @SerialName("pending")
    data class Pending(
        val mode: String,
    ) : RuntimeActivationPhase

    @Serializable
    @SerialName("claimed")
    data class Claimed(
        val identity: RuntimeAppliedUseIdentity,
        val kind: RuntimeClaimKind,
    ) : RuntimeActivationPhase

    @Serializable
    @SerialName("applied")
    data class Applied(
        val identity: RuntimeAppliedUseIdentity,
        val kind: RuntimeClaimKind,
    ) : RuntimeActivationPhase

    @Serializable
    @SerialName("terminated")
    data object Terminated : RuntimeActivationPhase
}

@Serializable
data class DurableCommandRecord(
    val generation: Long,
    val commandId: String,
    val origin: RuntimeCommandOrigin,
    val phase: RuntimeActivationPhase,
) {
    init {
        require(generation >= 0 && commandId.isNotBlank())
    }
}

/** Serializable service-envelope content; state validation makes copied or forged values inert. */
@Serializable
data class RuntimeActivationEnvelope(
    val generation: Long,
    val commandId: String,
    val origin: RuntimeCommandOrigin,
    val mode: String,
) {
    init {
        require(generation >= 0 && commandId.isNotBlank() && Mode.fromString(mode).preferenceValue == mode)
    }
}

sealed class DurableCommandReceipt protected constructor(
    internal val record: DurableCommandRecord,
) {
    val authority: PauseAuthorityRef get() = PauseAuthorityRef(record.generation)
    val commandId: String get() = record.commandId
    val origin: RuntimeCommandOrigin get() = record.origin
}

class ProfileActivationReceipt internal constructor(
    record: DurableCommandRecord,
) : DurableCommandReceipt(record)

class RuntimeActivationReceipt internal constructor(
    record: DurableCommandRecord,
) : DurableCommandReceipt(record) {
    val mode: Mode
        get() = Mode.fromString((record.phase as? RuntimeActivationPhase.Pending)?.mode ?: error("Unbound receipt"))

    fun envelope() = RuntimeActivationEnvelope(record.generation, record.commandId, record.origin, mode.preferenceValue)
}

class RuntimeStopReceipt internal constructor(
    record: DurableCommandRecord,
) : DurableCommandReceipt(record)

/** Immutable provenance carried from dispatch through native work and its acknowledgement. */
sealed interface RuntimeAppliedIntent {
    val receipt: RuntimeActivationReceipt

    data class Activation(
        override val receipt: RuntimeActivationReceipt,
    ) : RuntimeAppliedIntent

    data class Resume(
        val intent: PauseIntent,
        override val receipt: RuntimeActivationReceipt,
    ) : RuntimeAppliedIntent

    data class Continuation(
        override val receipt: RuntimeActivationReceipt,
        val lastPositiveIdentity: RuntimeAppliedUseIdentity,
    ) : RuntimeAppliedIntent

    data class Recovery(
        override val receipt: RuntimeActivationReceipt,
        val mode: Mode,
    ) : RuntimeAppliedIntent
}

internal fun DurableCommandRecord.initialClaimKind(): RuntimeClaimKind =
    when (val source = origin) {
        is RuntimeCommandOrigin.PauseResume -> {
            RuntimeClaimKind.Resume(source.token)
        }

        is RuntimeCommandOrigin.Recovery -> {
            RuntimeClaimKind.Recovery
        }

        RuntimeCommandOrigin.UserStart, RuntimeCommandOrigin.BootPolicy, is RuntimeCommandOrigin.StartupFallback -> {
            RuntimeClaimKind.Activation
        }

        is RuntimeCommandOrigin.MeasuredActivation -> {
            RuntimeClaimKind.Activation
        }

        is RuntimeCommandOrigin.ProfileMutation -> {
            check(source.mutationOrigin == ProfileMutationOrigin.ExplicitActivation)
            RuntimeClaimKind.Activation
        }

        else -> {
            error("Stopped command cannot claim activation")
        }
    }

internal fun RuntimeAppliedIntent.matchesClaim(
    kind: RuntimeClaimKind,
    recordsUse: Boolean,
): Boolean =
    when (this) {
        is RuntimeAppliedIntent.Activation -> {
            kind == RuntimeClaimKind.Activation
        }

        is RuntimeAppliedIntent.Resume -> {
            kind == RuntimeClaimKind.Resume(intent.token)
        }

        is RuntimeAppliedIntent.Recovery -> {
            kind == RuntimeClaimKind.Recovery
        }

        is RuntimeAppliedIntent.Continuation -> {
            kind == RuntimeClaimKind.Continuation(lastPositiveIdentity) &&
                !recordsUse
        }
    }
