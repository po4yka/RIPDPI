package com.poyka.ripdpi.data

import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PauseIntentAuthority private constructor(
    private val cell: PauseAuthorityStateCell,
    private val clock: PauseClock,
    val intentLinearizer: RuntimeIntentLinearizer,
) : PauseAuthorityQueries by PauseAuthorityQueryReader(cell),
    RuntimeCommandReservations by CheckedRuntimeCommandReservations(cell),
    RuntimeCommandApplications by CheckedRuntimeCommandApplications(cell),
    RuntimeCommandPolicies by CheckedRuntimeCommandPolicies(cell) {
    @Inject
    constructor(
        persistence: PauseAuthorityPersistence,
        clock: PauseClock,
        intentLinearizer: RuntimeIntentLinearizer,
    ) :
        this(PauseAuthorityStateCell(persistence, intentLinearizer), clock, intentLinearizer)

    val states: StateFlow<PauseAuthorityState?> get() = cell.states
    val profileUtility: ProfileUtilityAccess =
        CheckedProfileUtilityAccess(cell)

    fun initializeAfterMigration() =
        cell.locked {
            val before = cell.state.value
            when {
                before == null -> {
                    cell.publish(
                        PauseAuthorityState(0, null, null, profileUtility = ProfileUtilityState.empty()),
                    )
                }

                cell.hasReconstructedClaim() && before.command?.phase is RuntimeActivationPhase.Claimed -> {
                    val command = checkNotNull(before.command)
                    val recovery = before.keepsVerifiedRecoveryIntent(command)
                    cell.publish(
                        before.copy(
                            desired =
                                if (recovery) {
                                    DesiredRuntimeState.Running
                                } else if (before.pause == null) {
                                    DesiredRuntimeState.Stopped
                                } else {
                                    DesiredRuntimeState.Paused
                                },
                            desiredMode = if (recovery) before.desiredMode else before.pause?.mode,
                            pause =
                                before.pause?.copy(
                                    phase = PausePhase.Deferred,
                                    failure = PauseFailure.RuntimeRejected,
                                ),
                            command = command.copy(phase = RuntimeActivationPhase.Terminated),
                        ),
                    )
                    cell.completeReconstruction()
                }
            }
        }

    fun begin(
        mode: Mode,
        durationMillis: Long,
        expected: RuntimeAuthoritySnapshot,
    ): PauseIntent =
        cell.locked {
            check(snapshotAuthority() == expected) { "Pause intent was superseded" }
            require(durationMillis in AllowedDurations) { "Unsupported pause duration" }
            val now = clock.read()
            val boot = checkNotNull(now.bootCount) { "Pause clock unavailable" }
            check(now.wallMillis > 0 && now.elapsedMillis >= 0 && boot >= 0) { "Pause clock unavailable" }
            val before = cell.current()
            val generation = Math.addExact(before.generation, 1)
            val token = UUID.randomUUID().toString()
            val pause =
                PauseIntent(
                    generation,
                    token,
                    mode.preferenceValue,
                    now.wallMillis,
                    now.elapsedMillis,
                    Math.addExact(now.wallMillis, durationMillis),
                    Math.addExact(now.elapsedMillis, durationMillis),
                    boot,
                    PausePhase.Releasing,
                    null,
                )
            val command =
                DurableCommandRecord(
                    generation,
                    token,
                    RuntimeCommandOrigin.PauseResume(token),
                    RuntimeActivationPhase.Pending(mode.preferenceValue),
                )
            cell.publish(
                before.copy(
                    generation = generation,
                    pause = pause,
                    desired = DesiredRuntimeState.Paused,
                    desiredMode = mode.preferenceValue,
                    command = command,
                ),
            )
            pause
        }

    fun transition(
        intent: PauseIntent,
        phase: PausePhase,
        failure: PauseFailure?,
    ): Boolean =
        cell.locked {
            val before = cell.current()
            val active = before.pause ?: return@locked false
            if (!matchesRuntimeAuthority(active, intent)) return@locked false
            cell.publish(before.copy(pause = active.copy(phase = phase, failure = failure)))
            true
        }

    fun remainingMillis(intent: PauseIntent): Long? {
        val now = clock.read()
        if (now.bootCount != intent.bootCount || now.wallMillis < intent.createdWallMillis ||
            now.elapsedMillis < intent.createdElapsedMillis
        ) {
            return null
        }
        return (intent.deadlineElapsedMillis - now.elapsedMillis).coerceAtLeast(0)
    }

    /** Claim occurs before native work. Resume keeps its pause generation. */

    fun claimResume(
        intent: PauseIntent,
        immediate: Boolean,
    ): RuntimeActivationReceipt? =
        cell.locked {
            val before = cell.current()
            val active =
                before.pause ?: return@locked null
            val existing = before.command
            if (!matchesRuntimeAuthority(active, intent) ||
                active.phase !in setOf(PausePhase.Paused, PausePhase.Deferred)
            ) {
                return@locked null
            }
            val retry =
                existing?.phase == RuntimeActivationPhase.Terminated &&
                    existing.origin == RuntimeCommandOrigin.PauseResume(active.token)
            val matchingPending =
                existing?.let {
                    it.origin == RuntimeCommandOrigin.PauseResume(active.token) && isCommandPending(it, active.mode)
                } == true
            if (existing != null && !retry && !matchingPending) return@locked null
            val command =
                if (existing == null || retry) {
                    DurableCommandRecord(
                        before.generation,
                        UUID.randomUUID().toString(),
                        RuntimeCommandOrigin.PauseResume(active.token),
                        RuntimeActivationPhase.Pending(active.mode),
                    )
                } else {
                    existing
                }
            val remaining = remainingMillis(active)
            if (remaining == null &&
                !immediate
            ) {
                cell.publish(
                    before.copy(pause = active.copy(phase = PausePhase.Deferred, failure = PauseFailure.ClockChanged)),
                )
                return@locked null
            }
            if (!immediate && checkNotNull(remaining) > 0) return@locked null
            cell.publish(
                before.copy(
                    pause = active.copy(phase = PausePhase.Resuming, failure = null),
                    desiredMode = active.mode,
                    command = command,
                ),
            )
            RuntimeActivationReceipt(command)
        }

    companion object {
        val AllowedDurations = setOf(5 * 60_000L, 15 * 60_000L, 30 * 60_000L, 60 * 60_000L)
    }
}
