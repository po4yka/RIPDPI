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

        override fun selectedProfileId(groupId: String): StateFlow<String?> = flowFor(groupId).asStateFlow()

        override fun select(
            groupId: String,
            profileId: String,
        ) {
            synchronized(flows) {
                preferences.edit().putString(keyFor(groupId), profileId).apply()
                flowFor(groupId).value = profileId
            }
        }

        override fun clearSelection(groupId: String) {
            synchronized(flows) {
                preferences.edit().remove(keyFor(groupId)).apply()
                flowFor(groupId).value = null
            }
        }

        /** Clears every persisted selection. Intended for tests and reset flows. */
        fun clearAll() {
            preferences.edit().clear().apply()
            synchronized(flows) {
                flows.values.forEach { it.value = null }
            }
        }

        private fun flowFor(groupId: String): MutableStateFlow<String?> =
            synchronized(flows) {
                flows.getOrPut(groupId) {
                    MutableStateFlow(preferences.getString(keyFor(groupId), null))
                }
            }

        private fun keyFor(groupId: String): String = "$KeyPrefix$groupId"

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
