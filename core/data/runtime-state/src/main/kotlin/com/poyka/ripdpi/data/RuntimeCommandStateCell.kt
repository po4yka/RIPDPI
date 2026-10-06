package com.poyka.ripdpi.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface PauseAuthorityQueries {
    fun publicationPermit(
        receipt: RuntimeActivationReceipt,
        identity: RuntimeAppliedUseIdentity,
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

internal class PauseAuthorityQueryReader(
    private val cell: PauseAuthorityStateCell,
) : PauseAuthorityQueries {
    override fun publicationPermit(
        receipt: RuntimeActivationReceipt,
        identity: RuntimeAppliedUseIdentity,
    ) = cell.locked {
        val current = cell.current()
        val command = current.command ?: return@locked null
        if (matchesRuntimeAuthority(receipt, command) &&
            (command.phase as? RuntimeActivationPhase.Applied)?.identity == identity &&
            allowsRecovery(receipt.authority, receipt.mode)
        ) {
            RuntimePublicationPermit(receipt.authority, receipt.mode, receipt.commandId, identity)
        } else {
            null
        }
    }

    override fun allowsPublication(permit: RuntimePublicationPermit) =
        cell.locked {
            val command = cell.current().command
            command?.commandId == permit.commandId &&
                (command.phase as? RuntimeActivationPhase.Applied)?.identity == permit.appliedIdentity &&
                allowsRecovery(permit.authority, permit.mode)
        }

    override fun isInitialized() = cell.locked { cell.state.value != null }

    override fun reference() = cell.locked { PauseAuthorityRef(cell.current().generation) }

    override fun snapshot() = cell.locked { cell.state.value?.pause }

    override fun isCurrent(receipt: DurableCommandReceipt) =
        cell.locked {
            cell.state.value?.command?.let {
                it.generation ==
                    receipt.authority.generation &&
                    it.commandId == receipt.commandId &&
                    it.origin == receipt.origin
            } ==
                true
        }

    override fun isCurrent(intent: PauseIntent) =
        cell.locked {
            cell.state.value?.pause?.let {
                it.generation ==
                    intent.generation &&
                    it.token == intent.token
            } ==
                true
        }

    override fun snapshotAuthority() =
        cell.locked {
            cell.current().let {
                RuntimeAuthoritySnapshot(
                    PauseAuthorityRef(it.generation),
                    it.desired,
                    it.desiredMode,
                    it.pause,
                    it.command,
                )
            }
        }

    override fun allowsRecovery(
        reference: PauseAuthorityRef,
        mode: Mode,
    ) = cell.locked {
        val state = cell.current()
        val command = state.command
        state.generation == reference.generation &&
            state.pause == null &&
            state.desired == DesiredRuntimeState.Running &&
            state.desiredMode == mode.preferenceValue &&
            (
                command == null || command.recoveryEvidence(mode, state.profileUtility) != null
            )
    }
}

internal class PauseAuthorityStateCell(
    private val persistence: PauseAuthorityPersistence,
    private val linearizer: RuntimeIntentLinearizer,
) {
    private val lock = Any()
    private val loaded = persistence.read()
    private var reconstructionPending = loaded?.command?.phase is RuntimeActivationPhase.Claimed
    val state = MutableStateFlow(loaded)
    val states: StateFlow<PauseAuthorityState?> = state.asStateFlow()

    fun hasReconstructedClaim() = reconstructionPending

    fun completeReconstruction() {
        reconstructionPending = false
    }

    fun <T> locked(action: () -> T): T = linearizer.serialize { synchronized(lock) { action() } }

    fun current(): PauseAuthorityState = checkNotNull(state.value) { "Profile mutation migration is not complete" }

    fun publish(next: PauseAuthorityState) {
        persistence.commit(next)
        state.value = next
    }
}

interface ProfileUtilityAccess {
    val states: StateFlow<PauseAuthorityState?>

    fun invalidateCatalog()

    fun replaceCatalog(references: Set<ProfileUtilityReference>): Long

    fun setFavorite(
        reference: ProfileUtilityReference,
        favorite: Boolean,
    )
}

internal class CheckedProfileUtilityAccess(
    private val cell: PauseAuthorityStateCell,
) : ProfileUtilityAccess {
    override val states: StateFlow<PauseAuthorityState?> get() = cell.states

    override fun invalidateCatalog() =
        cell.locked {
            val before = cell.current()
            cell.publish(
                before.copy(
                    profileUtility =
                        before.profileUtility.copy(
                            catalogGeneration = Math.addExact(before.profileUtility.catalogGeneration, 1),
                            catalogReady = false,
                        ),
                ),
            )
        }

    override fun replaceCatalog(references: Set<ProfileUtilityReference>) =
        cell.locked {
            val before = cell.current()
            val utility = before.profileUtility.withCatalog(references)
            if (utility !=
                before.profileUtility
            ) {
                cell.publish(before.copy(profileUtility = utility))
            }
            utility.catalogGeneration
        }

    override fun setFavorite(
        reference: ProfileUtilityReference,
        favorite: Boolean,
    ) = cell.locked {
        val before = cell.current()
        check(before.profileUtility.catalogReady && reference in before.profileUtility.catalog) {
            "Favorite profile no longer exists"
        }
        val favorites =
            if (favorite) {
                before.profileUtility.favorites +
                    reference
            } else {
                before.profileUtility.favorites - reference
            }
        ; check(favorites.size <= MaxFavoriteProfiles) { "Favorite profile limit reached" }
        if (favorites !=
            before.profileUtility.favorites
        ) {
            cell.publish(before.copy(profileUtility = before.profileUtility.copy(favorites = favorites)))
        }
    }
}

private const val MaxFavoriteProfiles = 64

/** A failed owned recovery may mint a new recovery command only from a still-durable positive predecessor. */
internal fun DurableCommandRecord.recoveryEvidence(
    mode: Mode,
    utility: ProfileUtilityState,
): RuntimeAppliedUseIdentity? {
    val applied = (phase as? RuntimeActivationPhase.Applied)?.identity
    val retry =
        if (phase is RuntimeActivationPhase.Pending || phase == RuntimeActivationPhase.Terminated) {
            (origin as? RuntimeCommandOrigin.Recovery)?.priorAppliedIdentity
        } else {
            null
        }
    val identity = applied ?: retry
    return identity?.takeIf {
        it.mode == mode.preferenceValue &&
            utility.acknowledged.any { receipt -> receipt.identity == it }
    }
}

/** Failed recovery retains the earlier desired Running intent only with exact durable positive-ACK lineage. */
internal fun PauseAuthorityState.keepsVerifiedRecoveryIntent(command: DurableCommandRecord): Boolean {
    val predecessor = (command.origin as? RuntimeCommandOrigin.Recovery)?.priorAppliedIdentity
    return desired == DesiredRuntimeState.Running && predecessor != null && predecessor.mode == desiredMode &&
        profileUtility.acknowledged.any { it.identity == predecessor }
}
