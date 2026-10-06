package com.poyka.ripdpi.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface PauseClock {
    fun read(): PauseClockReading
}

interface PauseAuthorityPersistence {
    fun read(): PauseAuthorityState?

    /** Returning successfully proves the exact state was durably committed. */
    fun commit(state: PauseAuthorityState)
}

/** Owns no service/data locks or callbacks. Every transition commits before publication. */
@Singleton
class PauseIntentAuthority private constructor(
    private val cell: PauseAuthorityStateCell,
    private val clock: PauseClock,
    val intentLinearizer: RuntimeIntentLinearizer,
) : PauseAuthorityQueries by PauseAuthorityQueryReader(cell) {
    @Inject
    constructor(
        persistence: PauseAuthorityPersistence,
        clock: PauseClock,
        intentLinearizer: RuntimeIntentLinearizer,
    ) : this(PauseAuthorityStateCell(persistence, intentLinearizer), clock, intentLinearizer)

    val states: StateFlow<PauseAuthorityState?> get() = cell.states

    fun initializeAfterMigration() =
        cell.locked {
            if (cell.state.value == null) cell.publish(PauseAuthorityState(0, null, null))
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
            val intent =
                PauseIntent(
                    generation,
                    UUID.randomUUID().toString(),
                    mode.preferenceValue,
                    now.wallMillis,
                    now.elapsedMillis,
                    Math.addExact(now.wallMillis, durationMillis),
                    Math.addExact(now.elapsedMillis, durationMillis),
                    boot,
                    PausePhase.Releasing,
                    null,
                )
            cell.publish(
                before.copy(
                    generation = generation,
                    pause = intent,
                    desired = DesiredRuntimeState.Paused,
                    desiredMode = mode.preferenceValue,
                ),
            )
            intent
        }

    /**
     * Explicit destructive Reset discards a journal only after one checked Stopped commit.
     * Read/decode failure is never absence.
     */
    fun reserveResetStop(): DurableCommandReceipt =
        cell.locked {
            val before = cell.state.value
            val next =
                if (before == null) {
                    PauseAuthorityState(1, null, null, desired = DesiredRuntimeState.Stopped)
                } else {
                    before.copy(
                        generation = Math.addExact(before.generation, 1),
                        pause = null,
                        desired = DesiredRuntimeState.Stopped,
                        desiredMode = null,
                    )
                }
            cell.publish(next)
            DurableCommandReceipt(PauseAuthorityRef(next.generation))
        }

    fun supersede(command: RuntimeUserCommand): DurableCommandReceipt =
        cell.locked {
            val before = cell.current()
            val next =
                before.copy(
                    generation = Math.addExact(before.generation, 1),
                    pause = null,
                    desired =
                        if (command is RuntimeUserCommand.Start) {
                            DesiredRuntimeState.Running
                        } else {
                            DesiredRuntimeState.Stopped
                        },
                    desiredMode = (command as? RuntimeUserCommand.Start)?.mode?.preferenceValue,
                )
            cell.publish(next)
            DurableCommandReceipt(PauseAuthorityRef(next.generation))
        }

    /** Token-bound actions cannot consume another pause, including after a suspended recovery. */
    fun cancelMatchingPause(expected: PauseIntent): DurableCommandReceipt? =
        cell.locked {
            val before = cell.current()
            if (before.generation != expected.generation || before.pause?.token != expected.token) {
                null
            } else {
                supersede(RuntimeUserCommand.Stop)
            }
        }

    /** Cleanup retry owns a stopped generation, without restoring a cancelled resume intent. */
    fun cancelMatchingCleanup(expected: PauseAuthorityRef): DurableCommandReceipt? =
        cell.locked {
            val before = cell.current()
            if (before.generation != expected.generation || before.pause != null ||
                before.desired != DesiredRuntimeState.Stopped
            ) {
                null
            } else {
                supersede(RuntimeUserCommand.Stop)
            }
        }

    /** An old recovered after-image can replay, but can never cancel a newer user intent. */
    fun invalidateForMutation(
        origin: ProfileMutationOrigin,
        mutationId: String,
        expected: PauseAuthorityRef,
    ): ProfileMutationOutcome =
        cell.locked {
            val before = cell.current()
            if (!origin.supersedesPause) return@locked ProfileMutationOutcome.NonSuperseding
            if (before.lastMutationId == mutationId) {
                val recorded = before.lastMutationAuthority
                return@locked if (recorded != null && recorded.generation == before.generation) {
                    ProfileMutationOutcome.Reserved(DurableCommandReceipt(recorded))
                } else {
                    ProfileMutationOutcome.Superseded
                }
            }
            if (before.generation != expected.generation) return@locked ProfileMutationOutcome.Superseded
            val next = Math.addExact(before.generation, 1)
            val receipt = DurableCommandReceipt(PauseAuthorityRef(next))
            cell.publish(
                before.copy(
                    generation = next,
                    pause = null,
                    lastMutationId = mutationId,
                    lastMutationAuthority = receipt.authority,
                    desired =
                        if (before.pause != null || origin == ProfileMutationOrigin.Reset ||
                            origin == ProfileMutationOrigin.RestoreProfiles
                        ) {
                            DesiredRuntimeState.Stopped
                        } else {
                            before.desired
                        },
                ),
            )
            ProfileMutationOutcome.Reserved(receipt)
        }

    fun transition(
        intent: PauseIntent,
        phase: PausePhase,
        failure: PauseFailure?,
    ): Boolean =
        cell.locked {
            val before = cell.current()
            val active = before.pause ?: return@locked false
            if (!matches(active, intent)) return@locked false
            cell.publish(before.copy(pause = active.copy(phase = phase, failure = failure)))
            true
        }

    fun remainingMillis(intent: PauseIntent): Long? {
        val now = clock.read()
        if (now.bootCount != intent.bootCount ||
            now.wallMillis < intent.createdWallMillis || now.elapsedMillis < intent.createdElapsedMillis
        ) {
            return null
        }
        return (intent.deadlineElapsedMillis - now.elapsedMillis).coerceAtLeast(0)
    }

    fun claimResume(
        intent: PauseIntent,
        immediate: Boolean,
    ): Boolean =
        cell.locked {
            val before = cell.current()
            val active = before.pause ?: return@locked false
            if (!matches(active, intent) ||
                active.phase !in setOf(PausePhase.Paused, PausePhase.Deferred)
            ) {
                return@locked false
            }
            val remaining = remainingMillis(active)
            if (remaining == null && !immediate) {
                cell.publish(
                    before.copy(
                        pause = active.copy(phase = PausePhase.Deferred, failure = PauseFailure.ClockChanged),
                    ),
                )
                return@locked false
            }
            if (!immediate && checkNotNull(remaining) > 0) return@locked false
            cell.publish(before.copy(pause = active.copy(phase = PausePhase.Resuming, failure = null)))
            true
        }

    /** Sole successful terminal transition: a matching positive applied-runtime receipt. */
    fun acknowledgeResume(
        intent: PauseIntent,
        mode: Mode,
    ): Boolean =
        cell.locked {
            val before = cell.current()
            val active = before.pause ?: return@locked false
            if (!matches(active, intent) || active.phase != PausePhase.Resuming ||
                active.mode != mode.preferenceValue
            ) {
                return@locked false
            }
            cell.publish(
                before.copy(
                    pause = null,
                    desired = DesiredRuntimeState.Running,
                    desiredMode = mode.preferenceValue,
                ),
            )
            true
        }

    fun authorizeBootPolicyStart(
        mode: Mode,
        expected: RuntimeAuthoritySnapshot,
    ): BootPolicyStartReceipt? =
        cell.locked {
            if (snapshotAuthority() != expected || expected.pause != null) return@locked null
            val before = cell.current()
            val generation = Math.addExact(before.generation, 1)
            cell.publish(
                before.copy(
                    generation = generation,
                    desired = DesiredRuntimeState.Running,
                    desiredMode = mode.preferenceValue,
                ),
            )
            BootPolicyStartReceipt(PauseAuthorityRef(generation))
        }

    fun confirmAppliedMode(
        reference: PauseAuthorityRef,
        mode: Mode,
    ): Boolean =
        cell.locked {
            val before = cell.current()
            if (before.generation != reference.generation || before.pause != null ||
                before.desired != DesiredRuntimeState.Running
            ) {
                return@locked false
            }
            if (before.desiredMode !=
                mode.preferenceValue
            ) {
                cell.publish(before.copy(desiredMode = mode.preferenceValue))
            }
            true
        }

    fun finishOwnedRuntime(receipt: DurableCommandReceipt): Boolean =
        cell.locked {
            val before = cell.current()
            if (before.generation != receipt.authority.generation || before.pause != null ||
                before.desired != DesiredRuntimeState.Running
            ) {
                return@locked false
            }
            cell.publish(before.copy(desired = DesiredRuntimeState.Stopped))
            true
        }

    private fun matches(
        first: PauseIntent,
        second: PauseIntent,
    ) = first.generation == second.generation && first.token == second.token

    companion object {
        val AllowedDurations = setOf(5 * 60_000L, 15 * 60_000L, 30 * 60_000L, 60 * 60_000L)
    }
}

interface PauseAuthorityQueries {
    fun publicationPermit(
        reference: PauseAuthorityRef,
        mode: Mode,
    ): RuntimePublicationPermit?

    fun allowsPublication(permit: RuntimePublicationPermit): Boolean

    fun isInitialized(): Boolean

    fun reference(): PauseAuthorityRef

    fun snapshot(): PauseIntent?

    fun isCurrent(receipt: DurableCommandReceipt): Boolean

    fun isCurrent(intent: PauseIntent): Boolean

    fun snapshotAuthority(): RuntimeAuthoritySnapshot

    fun allowsRecovery(
        reference: PauseAuthorityRef,
        mode: Mode,
    ): Boolean
}

private class PauseAuthorityQueryReader(
    private val cell: PauseAuthorityStateCell,
) : PauseAuthorityQueries {
    override fun publicationPermit(
        reference: PauseAuthorityRef,
        mode: Mode,
    ): RuntimePublicationPermit? =
        cell.locked {
            if (allowsRecovery(reference, mode)) RuntimePublicationPermit(reference, mode) else null
        }

    override fun allowsPublication(permit: RuntimePublicationPermit): Boolean =
        allowsRecovery(permit.authority, permit.mode)

    override fun isInitialized(): Boolean = cell.locked { cell.state.value != null }

    /** Called only under ProfileMutationRecoveryAccess after completing legacy journal replay. */
    override fun reference(): PauseAuthorityRef = cell.locked { PauseAuthorityRef(cell.current().generation) }

    override fun snapshot(): PauseIntent? = cell.locked { cell.state.value?.pause }

    override fun isCurrent(receipt: DurableCommandReceipt): Boolean =
        cell.locked {
            cell.state.value?.generation ==
                receipt.authority.generation
        }

    override fun isCurrent(intent: PauseIntent): Boolean =
        cell.locked {
            cell.state.value
                ?.pause
                ?.let { matches(it, intent) } ==
                true
        }

    /** Elapsed time controls same-boot expiry; wall-clock rollback or unverifiable boot fails closed. */
    override fun snapshotAuthority(): RuntimeAuthoritySnapshot =
        cell.locked {
            val before = cell.current()
            RuntimeAuthoritySnapshot(
                PauseAuthorityRef(before.generation),
                before.desired,
                before.desiredMode,
                before.pause,
            )
        }

    override fun allowsRecovery(
        reference: PauseAuthorityRef,
        mode: Mode,
    ): Boolean =
        cell.locked {
            val before = cell.current()
            before.generation == reference.generation && before.pause == null &&
                before.desired == DesiredRuntimeState.Running &&
                before.desiredMode == mode.preferenceValue
        }

    private fun matches(
        first: PauseIntent,
        second: PauseIntent,
    ) = first.generation == second.generation && first.token == second.token
}

/** One checked persistence/publication cell shared by queries and all durable commands. */
private class PauseAuthorityStateCell(
    private val persistence: PauseAuthorityPersistence,
    private val linearizer: RuntimeIntentLinearizer,
) {
    private val lock = Any()
    val state = MutableStateFlow(persistence.read())
    val states: StateFlow<PauseAuthorityState?> = state.asStateFlow()

    fun <T> locked(action: () -> T): T = linearizer.serialize { synchronized(lock) { action() } }

    fun current(): PauseAuthorityState = checkNotNull(state.value) { "Profile mutation migration is not complete" }

    fun publish(next: PauseAuthorityState) {
        persistence.commit(next)
        state.value = next
    }
}

@Singleton
class AndroidPauseClock
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : PauseClock {
        override fun read() =
            PauseClockReading(
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime(),
                runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull(),
            )
    }

@Singleton
class CheckedPauseAuthorityPersistence
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : PauseAuthorityPersistence {
        private val preferences = context.getSharedPreferences("pause_intent_authority", Context.MODE_PRIVATE)
        private val json = com.poyka.ripdpi.serialization.RipDpiContractJson

        override fun read(): PauseAuthorityState? =
            preferences.getString("state", null)?.let {
                json.decodeFromString(PauseAuthorityState.serializer(), it)
            }

        override fun commit(state: PauseAuthorityState) {
            check(
                preferences
                    .edit()
                    .putString(
                        "state",
                        json.encodeToString(PauseAuthorityState.serializer(), state),
                    ).commit(),
            ) {
                "Pause intent persistence failed"
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class PauseAuthorityModule {
    @Binds abstract fun persistence(value: CheckedPauseAuthorityPersistence): PauseAuthorityPersistence

    @Binds abstract fun clock(value: AndroidPauseClock): PauseClock
}
