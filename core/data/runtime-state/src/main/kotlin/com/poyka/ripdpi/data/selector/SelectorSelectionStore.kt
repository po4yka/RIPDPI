package com.poyka.ripdpi.data.selector

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    fun select(
        groupId: String,
        profileId: String,
    )

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
    ) : SelectorSelectionStore {
        private val preferences = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        private val flows = HashMap<String, MutableStateFlow<String?>>()
        private val revisions = HashMap<String, Long>()

        override fun selectedProfileId(groupId: String): StateFlow<String?> = flowFor(groupId).asStateFlow()

        override fun snapshot(groupId: String): SelectorSelectionSnapshot =
            synchronized(flows) {
                val profileId = flowFor(groupId).value
                SelectorSelectionSnapshot(
                    profileId = profileId,
                    isManual = profileId != null && preferences.getBoolean(manualKeyFor(groupId), false),
                    revision = revisions[groupId] ?: 0L,
                )
            }

        override fun invalidatePendingSelection(groupId: String) {
            synchronized(flows) {
                advanceRevision(groupId)
            }
        }

        override fun selectAutomatically(
            groupId: String,
            expected: SelectorSelectionSnapshot,
            profileId: String,
        ): Boolean =
            synchronized(flows) {
                if (snapshot(groupId) != expected) {
                    false
                } else {
                    writeSelection(groupId, profileId, isManual = false)
                    true
                }
            }

        override fun select(
            groupId: String,
            profileId: String,
        ) {
            synchronized(flows) {
                writeSelection(groupId, profileId, isManual = true)
            }
        }

        override fun clearSelection(groupId: String) {
            synchronized(flows) {
                preferences
                    .edit()
                    .remove(keyFor(groupId))
                    .remove(manualKeyFor(groupId))
                    .apply()
                advanceRevision(groupId)
                flowFor(groupId).value = null
            }
        }

        /** Clears every persisted selection. Intended for tests and reset flows. */
        fun clearAll() {
            synchronized(flows) {
                preferences.edit().clear().commit()
                flows.forEach { (groupId, flow) ->
                    advanceRevision(groupId)
                    flow.value = null
                }
            }
        }

        private fun flowFor(groupId: String): MutableStateFlow<String?> =
            synchronized(flows) {
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
                .apply()
            advanceRevision(groupId)
            flowFor(groupId).value = profileId
        }

        private fun advanceRevision(groupId: String) {
            revisions[groupId] = (revisions[groupId] ?: 0L) + 1L
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
