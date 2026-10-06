package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable

/** Semantic intent is mandatory: selecting an edited after-image does not imply activation. */
@Serializable
enum class ProfileMutationOrigin(
    val supersedesPause: Boolean,
) {
    SavedEdit(false),
    ImportOnly(false),
    SubscriptionRefresh(false),
    Bootstrap(false),
    InternalReconcile(false),
    Compensation(false),
    ExplicitActivation(true),
    ExplicitDeletion(true),
    Reset(true),
    RestoreProfiles(true),
}

@Serializable
data class PauseAuthorityRef(
    val generation: Long,
)

@Serializable
enum class DesiredRuntimeState { LegacyUnknown, Running, Stopped, Paused }

sealed interface ProfileMutationOutcome {
    data class Reserved(
        val receipt: DurableCommandReceipt,
    ) : ProfileMutationOutcome

    data object NonSuperseding : ProfileMutationOutcome

    data object Superseded : ProfileMutationOutcome
}

@Serializable
enum class PausePhase { Releasing, Paused, Resuming, Deferred, CleanupPending }

@Serializable
enum class PauseFailure {
    ClockUnavailable,
    ClockChanged,
    ConsentRequired,
    Lockdown,
    BackgroundRestricted,
    UserStopped,
    RuntimeRejected,
}

@Serializable
data class PauseIntent(
    val generation: Long,
    val token: String,
    val mode: String,
    val createdWallMillis: Long,
    val createdElapsedMillis: Long,
    val deadlineWallMillis: Long,
    val deadlineElapsedMillis: Long,
    val bootCount: Int,
    val phase: PausePhase,
    val failure: PauseFailure?,
) {
    val reference: PauseAuthorityRef get() = PauseAuthorityRef(generation)
}

@Serializable
data class PauseAuthorityState(
    val generation: Long,
    val pause: PauseIntent?,
    val lastMutationId: String?,
    val lastMutationAuthority: PauseAuthorityRef? = null,
    val desired: DesiredRuntimeState = DesiredRuntimeState.LegacyUnknown,
    val desiredMode: String? = null,
    val profileUtility: ProfileUtilityState,
    /** Null means an old/unknown authority record and grants no activation capability. */
    val command: DurableCommandRecord? = null,
) {
    override fun toString(): String =
        "PauseAuthorityState(generation=$generation, desired=$desired, phase=${pause?.phase})"
}

data class PauseClockReading(
    val wallMillis: Long,
    val elapsedMillis: Long,
    val bootCount: Int?,
)
