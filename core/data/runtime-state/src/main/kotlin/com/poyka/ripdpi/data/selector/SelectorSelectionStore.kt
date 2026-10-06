package com.poyka.ripdpi.data.selector

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** A selection observation used to reject stale asynchronous probe decisions. */
data class SelectorSelectionSnapshot(
    val profileId: String?,
    val isManual: Boolean,
    val revision: Long,
)

/**
 * The selected-member signal for selector [com.poyka.ripdpi.data.ProxyGroup]s.
 *
 * When a group is a selector (`isSelector == true`), exactly one of its member
 * profiles is "active". This store owns that choice: it exposes a reactive
 * [selectedProfileId] flow per group and persists the selection so a service
 * restart resumes the last-selected member rather than falling back to the
 * first profile in the group.
 *
 * Mirrors NekoBox's selector-outbound selection, where the active member id is
 * persisted and re-applied on restart.
 */
interface SelectorSelectionStore {
    /**
     * Hot stream of the currently-selected member profile id for [groupId], or
     * `null` when the group has no selection yet. Re-emits on every [select] /
     * [clearSelection] for that group.
     */
    fun selectedProfileId(groupId: String): StateFlow<String?>

    fun snapshot(groupId: String): SelectorSelectionSnapshot

    /** Invalidates in-flight probe decisions without changing the selected member or its origin. */
    fun invalidatePendingSelection(groupId: String)

    /** Applies a probe result only while [expected] is still the current selection. */
    fun selectAutomatically(
        groupId: String,
        expected: SelectorSelectionSnapshot,
        profileId: String,
    ): Boolean

    /** Sets [profileId] as the active member of the selector group [groupId]. */
    suspend fun select(
        groupId: String,
        profileId: String,
    )

    /** Publishes a selection using the original checked activation reservation. */
    fun selectReserved(
        groupId: String,
        profileId: String,
        receipt: com.poyka.ripdpi.data.DurableCommandReceipt,
    ): Boolean

    fun manualReceipt(groupId: String): com.poyka.ripdpi.data.DurableCommandReceipt?

    /** Drops the persisted selection for [groupId]. No-op when absent. */
    fun clearSelection(groupId: String)
}

/**
 * [SelectorSelectionStore] backed by a private `SharedPreferences` file. Each
 * group's selection is stored under a per-group key, and an in-memory
 * [MutableStateFlow] per observed group fans changes out to collectors.
 */
@Singleton
class SharedPreferencesSelectorSelectionStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val intentPreparation: com.poyka.ripdpi.data.PauseMutationPreparationSource,
        private val authority: com.poyka.ripdpi.data.PauseIntentAuthority,
        private val activeGroup: SelectorActiveGroupStore,
    ) : SelectorSelectionStore {
        private val preferences = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        private val flows = HashMap<String, MutableStateFlow<String?>>()
        private val revisions = HashMap<String, Long>()
        private var clearRevision = 0L
        private val manualReceipts = HashMap<String, com.poyka.ripdpi.data.DurableCommandReceipt>()

        override fun selectedProfileId(groupId: String): StateFlow<String?> =
            object : StateFlow<String?> {
                override val value: String? get() = preferences.getString(keyFor(groupId), null)
                override val replayCache: List<String?> get() = listOf(value)

                @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
                override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<String?>): Nothing {
                    kotlinx.coroutines.flow
                        .combine(activeGroup.choiceChanges, flowFor(groupId)) { _, _ -> value }
                        .distinctUntilChanged()
                        .collect(collector)
                    kotlinx.coroutines.awaitCancellation()
                }
            }

        override fun snapshot(groupId: String): SelectorSelectionSnapshot =
            serializeSelection {
                val profileId = preferences.getString(keyFor(groupId), null)
                SelectorSelectionSnapshot(
                    profileId = profileId,
                    isManual = profileId != null && preferences.getBoolean(manualKeyFor(groupId), false),
                    revision =
                        Math.addExact(
                            Math.addExact(revisions[groupId] ?: 0L, activeGroup.choiceChanges.value),
                            clearRevision,
                        ),
                )
            }

        override fun invalidatePendingSelection(groupId: String) {
            serializeSelection {
                advanceRevision(groupId)
            }
        }

        override fun selectAutomatically(
            groupId: String,
            expected: SelectorSelectionSnapshot,
            profileId: String,
        ): Boolean =
            serializeSelection {
                if (snapshot(groupId) != expected) {
                    false
                } else {
                    manualReceipts.remove(groupId)
                    writeSelection(groupId, profileId, isManual = false)
                    true
                }
            }

        override suspend fun select(
            groupId: String,
            profileId: String,
        ) {
            val preparation =
                intentPreparation.captureMutation(
                    com.poyka.ripdpi.data.ProfileMutationOrigin.ExplicitActivation,
                )
            val outcome = intentPreparation.activateSelector(preparation, groupId, profileId, activeGroup)
            if (outcome is com.poyka.ripdpi.data.ProfileMutationOutcome.Reserved) {
                authority.intentLinearizer.serialize {
                    if (authority.isCurrent(outcome.receipt)) {
                        serializeSelection {
                            advanceRevision(groupId)
                            flowFor(groupId).value = preferences.getString(keyFor(groupId), null)
                        }
                    }
                }
            }
        }

        override fun manualReceipt(groupId: String) = activeGroup.manualReceipt(groupId)

        override fun selectReserved(
            groupId: String,
            profileId: String,
            receipt: com.poyka.ripdpi.data.DurableCommandReceipt,
        ): Boolean =
            authority.intentLinearizer.serialize {
                if (!authority.isCurrent(receipt)) return@serialize false
                serializeSelection {
                    val profileReceipt = receipt as? com.poyka.ripdpi.data.ProfileActivationReceipt
                    if (profileReceipt == null || authority.snapshotAuthority().command?.phase !=
                        com.poyka.ripdpi.data.RuntimeActivationPhase.Unbound
                    ) {
                        false
                    } else {
                        activeGroup.commitMember(groupId, profileId, SelectorChoiceOrigin.Manual(profileReceipt))
                        manualReceipts[groupId] = receipt
                        advanceRevision(groupId)
                        flowFor(groupId).value = profileId
                        true
                    }
                }
            }

        override fun clearSelection(groupId: String) {
            serializeSelection {
                preferences
                    .edit()
                    .remove(keyFor(groupId))
                    .remove(manualKeyFor(groupId))
                    .commit()
                    .also { check(it) { "Selector selection persistence failed" } }
                activeGroup.forgetManualReceipt(groupId)
                manualReceipts.remove(groupId)
                advanceRevision(groupId)
                flowFor(groupId).value = null
            }
        }

        /** Clears every persisted selection. Intended for tests and reset flows. */
        fun clearAll() {
            serializeSelection {
                val nextClear = Math.addExact(clearRevision, 1L)
                check(preferences.edit().clear().commit()) { "Selector selection persistence failed" }
                activeGroup.onSelectionsCleared()
                manualReceipts.clear()
                clearRevision = nextClear
                flows.values.forEach { it.value = null }
            }
        }

        /** Same gate as measured commitMember: snapshot comparison and one checked write are indivisible. */
        private fun <T> serializeSelection(block: () -> T): T =
            authority.intentLinearizer.serialize { synchronized(flows, block) }

        private fun flowFor(groupId: String): MutableStateFlow<String?> =
            serializeSelection {
                flows.getOrPut(groupId) {
                    MutableStateFlow(preferences.getString(keyFor(groupId), null))
                }
            }

        // Called only under the flows monitor so provenance and the selected ID form one observation.
        private fun writeSelection(
            groupId: String,
            profileId: String,
            isManual: Boolean,
        ) {
            preferences
                .edit()
                .putString(keyFor(groupId), profileId)
                .putBoolean(manualKeyFor(groupId), isManual)
                .commit()
                .also { check(it) { "Selector selection persistence failed" } }
            if (!isManual) activeGroup.forgetManualReceipt(groupId)
            advanceRevision(groupId)
            flowFor(groupId).value = profileId
        }

        private fun advanceRevision(groupId: String) {
            revisions[groupId] = Math.addExact(revisions[groupId] ?: 0L, 1L)
        }

        private fun keyFor(groupId: String): String = "$KeyPrefix$groupId"

        private fun manualKeyFor(groupId: String): String = "manual-selection-$groupId"

        private companion object {
            const val PrefsName = "selector_selection_store"
            const val KeyPrefix = "selected-profile-"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class SelectorSelectionStoreModule {
    @Binds
    @Singleton
    abstract fun bindSelectorSelectionStore(store: SharedPreferencesSelectorSelectionStore): SelectorSelectionStore
}
