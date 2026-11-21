package com.poyka.ripdpi.data.selector

import com.poyka.ripdpi.data.DurableCommandReceipt
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.PauseMutationPreparationSource
import com.poyka.ripdpi.data.ProfileActivationReceipt
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.RuntimeActivationPhase
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class SelectorSelectionSnapshot(
    val profileId: String?,
    val isManual: Boolean,
    val revision: Long,
)

interface SelectorSelectionStore {
    /**
     * Hot stream of the currently-selected member profile id for [groupId], or
     * `null` when the group has no selection yet. Re-emits on every [select] /
     * [clearSelection] for that group.
     */
    fun selectedProfileId(groupId: String): Flow<String?>

    suspend fun snapshot(groupId: String): SelectorSelectionSnapshot

    /** Hot stream of the selection of [groupId], including whether the user pinned it by hand. */
    fun selection(groupId: String): Flow<SelectorSelectionSnapshot>

    /** Keeps the selected member and lets the group's failover policy move it again. */
    suspend fun resumeAutomatic(groupId: String)

    /** Invalidates in-flight probe decisions without changing the selected member or its origin. */
    fun invalidatePendingSelection(groupId: String)

    /** Applies a probe result only while [expected] is still the current, automatic selection. */
    suspend fun selectAutomatically(
        groupId: String,
        expected: SelectorSelectionSnapshot,
        profileId: String,
        isCurrent: () -> Boolean = { true },
    ): Boolean

    /** Sets [profileId] as the active member of the selector group [groupId]. */
    suspend fun select(
        groupId: String,
        profileId: String,
    )

    /** Publishes a selection using the original checked activation reservation. */
    suspend fun selectReserved(
        groupId: String,
        profileId: String,
        receipt: com.poyka.ripdpi.data.DurableCommandReceipt,
    ): Boolean

    fun manualReceipt(groupId: String): com.poyka.ripdpi.data.DurableCommandReceipt?

    /** Drops the persisted selection for [groupId]. No-op when absent. */
    suspend fun clearSelection(groupId: String)
}

@Singleton
class DefaultSelectorSelectionStore
    @Inject
    constructor(
        private val persistence: SelectorPersistence,
        private val intentPreparation: PauseMutationPreparationSource,
        private val authority: PauseIntentAuthority,
        private val activeGroup: SelectorActiveGroupStore,
    ) : SelectorSelectionStore {
        private val revisions = mutableMapOf<String, Long>()

        override fun selectedProfileId(groupId: String): Flow<String?> =
            persistence.observeChoice(groupId).map { it.profileId }

        override suspend fun snapshot(groupId: String): SelectorSelectionSnapshot =
            persistence.transaction { observed(groupId, it.choice(groupId)) }

        override fun selection(groupId: String): Flow<SelectorSelectionSnapshot> = persistence.observeChoice(groupId)

        override suspend fun resumeAutomatic(groupId: String) =
            persistence.transaction(afterCommit = { activeGroup.forgetManualReceipt(groupId) }) { rows ->
                rows.setChoice(groupId, rows.choice(groupId).profileId, false)
            }

        override fun invalidatePendingSelection(groupId: String) {
            authority.intentLinearizer.serialize {
                revisions[groupId] = Math.addExact(revisions[groupId] ?: 0L, 1L)
            }
        }

        override suspend fun selectAutomatically(
            groupId: String,
            expected: SelectorSelectionSnapshot,
            profileId: String,
            isCurrent: () -> Boolean,
        ): Boolean =
            persistence.transaction(
                afterCommit = { selected -> if (selected) activeGroup.forgetManualReceipt(groupId) },
            ) { rows ->
                val current = rows.choice(groupId)
                // A manual pick pins its member: only an automatic selection may move automatically.
                if (current.isManual || observed(groupId, current) != expected || !isCurrent()) {
                    false
                } else {
                    rows.setChoice(groupId, profileId, false)
                    true
                }
            }

        override suspend fun select(
            groupId: String,
            profileId: String,
        ) {
            val preparation = intentPreparation.captureMutation(ProfileMutationOrigin.ExplicitActivation)
            intentPreparation.activateSelector(preparation, groupId, profileId, activeGroup)
        }

        override fun manualReceipt(groupId: String) = activeGroup.manualReceipt(groupId)

        override suspend fun selectReserved(
            groupId: String,
            profileId: String,
            receipt: DurableCommandReceipt,
        ): Boolean =
            persistence.transaction(
                afterCommit = { selected ->
                    if (selected) {
                        activeGroup.publishCommittedChoice(
                            groupId,
                            SelectorChoiceOrigin.Manual(receipt as ProfileActivationReceipt),
                        )
                    }
                },
            ) { rows ->
                if (receipt !is ProfileActivationReceipt || !authority.isCurrent(receipt) ||
                    authority.snapshotAuthority().command?.phase != RuntimeActivationPhase.Unbound
                ) {
                    false
                } else {
                    rows.setChoice(groupId, profileId, true)
                    rows.setActiveGroup(groupId)
                    true
                }
            }

        override suspend fun clearSelection(groupId: String) =
            persistence.transaction(
                afterCommit = { activeGroup.forgetManualReceipt(groupId) },
            ) { it.setChoice(groupId, null, false) }

        suspend fun clearAll() =
            persistence.transaction(
                afterCommit = { activeGroup.clearManualReceipts() },
            ) { it.clearSelections() }

        private fun observed(
            groupId: String,
            stored: SelectorSelectionSnapshot,
        ) = stored.copy(revision = Math.addExact(stored.revision, revisions[groupId] ?: 0L))
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class SelectorSelectionStoreModule {
    @Binds
    @Singleton
    abstract fun bindSelectorSelectionStore(store: DefaultSelectorSelectionStore): SelectorSelectionStore
}
