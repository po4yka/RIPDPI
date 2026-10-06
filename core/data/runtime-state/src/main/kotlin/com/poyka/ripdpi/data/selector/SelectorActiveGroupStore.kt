package com.poyka.ripdpi.data.selector

import android.content.Context
import com.poyka.ripdpi.data.DurableCommandReceipt
import com.poyka.ripdpi.data.PauseIntentAuthority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Explicit ownership of the live selector; null is an intentional standalone selection. */
@Singleton
class SelectorActiveGroupStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val authority: PauseIntentAuthority,
    ) : SelectorChoicePersistence {
        private val preferences = context.getSharedPreferences("selector_selection_store", Context.MODE_PRIVATE)
        private val manual = mutableMapOf<String, com.poyka.ripdpi.data.ProfileActivationReceipt>()
        private val changes = MutableStateFlow(0L)
        val choiceChanges = changes.asStateFlow()
        private val active = MutableStateFlow(preferences.getString(GroupKey, null)?.takeIf(String::isNotBlank))
        val activeGroupId: StateFlow<String?> = active.asStateFlow()

        /** Migrate the first valid persisted legacy choice once; later imports never activate a group. */
        fun initializeLegacyGroup(orderedGroups: List<com.poyka.ripdpi.data.ProxyGroup>) {
            serializeChoice {
                if (!preferences.contains(GroupKey)) {
                    val legacy =
                        orderedGroups.firstOrNull { group ->
                            val selected = preferences.getString("selected-profile-${group.id}", null)
                            group.isSelector && selected != null &&
                                group.members.any {
                                    it.id == selected && it.groupId == group.id
                                }
                        }
                    write(legacy?.id)
                }
            }
        }

        fun select(
            groupId: String?,
            receipt: DurableCommandReceipt,
        ): Boolean =
            authority.intentLinearizer.serialize {
                if (!authority.isCurrent(receipt)) return@serialize false
                serializeChoice { write(groupId) }
                true
            }

        /** Member, provenance and active group use one checked commit; receipt remains transient. */
        override fun commitMember(
            groupId: String,
            memberId: String,
            origin: SelectorChoiceOrigin,
        ) {
            serializeChoice {
                val next = Math.addExact(changes.value, 1)
                check(
                    preferences
                        .edit()
                        .putString(GroupKey, groupId)
                        .putString("selected-profile-$groupId", memberId)
                        .putBoolean("manual-selection-$groupId", true)
                        .commit(),
                ) {
                    "Selector selection persistence failed"
                }
                when (origin) {
                    is SelectorChoiceOrigin.Manual -> manual[groupId] = origin.receipt
                    SelectorChoiceOrigin.Reconstruction -> manual.remove(groupId)
                }
                active.value = groupId
                changes.value = next
            }
        }

        internal fun manualReceipt(groupId: String) = serializeChoice { manual[groupId] }

        override fun clearStandalone() = serializeChoice { write(null) }

        override fun snapshotActiveGroupId(): String? = active.value

        override fun restoreActiveGroup(groupId: String?) =
            serializeChoice {
                manual.clear()
                write(groupId)
            }

        /** Catalog deletion/restore keeps only an explicitly existing group and never picks a replacement. */
        override fun prune(groupIds: Set<String>) {
            serializeChoice {
                if (active.value != null && active.value !in groupIds) write(null)
            }
        }

        internal fun forgetManualReceipt(groupId: String) =
            serializeChoice {
                manual.remove(groupId)
                Unit
            }

        /** Called after the owning selection store has checked the single preferences clear commit. */
        internal fun onSelectionsCleared() =
            serializeChoice {
                manual.clear()
                active.value = null
            }

        /** The same authority gate coordinates automatic compare/write with measured after-image replay. */
        private fun <T> serializeChoice(block: () -> T): T =
            authority.intentLinearizer.serialize { synchronized(active, block) }

        private fun write(groupId: String?) {
            check(preferences.edit().putString(GroupKey, groupId.orEmpty()).commit()) {
                "Active selector persistence failed"
            }
            active.value = groupId
        }

        companion object {
            private const val GroupKey = "active_group_id"
        }
    }

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class SelectorChoiceModule {
    @dagger.Binds abstract fun choices(store: SelectorActiveGroupStore): SelectorChoicePersistence
}
