package com.poyka.ripdpi.data.selector

import com.poyka.ripdpi.data.DurableCommandReceipt
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.ProfileActivationReceipt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Saved ownership is transactional; activation capabilities remain process-local. */
@Singleton
class SelectorActiveGroupStore
    @Inject
    constructor(
        private val persistence: SelectorPersistence,
        private val authority: PauseIntentAuthority,
    ) : SelectorChoicePersistence {
        private val manual = mutableMapOf<String, ProfileActivationReceipt>()
        private val changes = MutableStateFlow(0L)
        val choiceChanges = changes.asStateFlow()
        val activeGroupId = persistence.observeActiveGroup()

        suspend fun select(
            groupId: String?,
            receipt: DurableCommandReceipt,
        ): Boolean =
            persistence.transaction { rows ->
                if (!authority.isCurrent(receipt)) {
                    false
                } else {
                    rows.setActiveGroup(groupId)
                    true
                }
            }

        override suspend fun commitMember(
            groupId: String,
            memberId: String,
            origin: SelectorChoiceOrigin,
        ) {
            persistence.transaction(
                afterCommit = { committed -> if (committed) publishCommittedChoice(groupId, origin) },
            ) { rows ->
                if (origin is SelectorChoiceOrigin.Manual && !authority.isCurrent(origin.receipt)) {
                    false
                } else {
                    rows.setChoice(groupId, memberId, true)
                    rows.setActiveGroup(groupId)
                    true
                }
            }
        }

        /** Called after the database commit, never from an intermediate row write. */
        fun publishCommittedChoice(
            groupId: String,
            origin: SelectorChoiceOrigin,
        ) {
            authority.intentLinearizer.serialize {
                when (origin) {
                    is SelectorChoiceOrigin.Manual -> {
                        if (authority.isCurrent(origin.receipt)) {
                            manual[groupId] = origin.receipt
                        }
                    }

                    SelectorChoiceOrigin.Reconstruction -> {
                        manual.remove(groupId)
                    }
                }
                changes.value = Math.addExact(changes.value, 1L)
            }
        }

        internal fun manualReceipt(groupId: String) = authority.intentLinearizer.serialize { manual[groupId] }

        override suspend fun clearStandalone() = persistence.transaction { it.setActiveGroup(null) }

        override suspend fun snapshotActiveGroupId(): String? = persistence.transaction { it.activeGroup() }

        override suspend fun restoreActiveGroup(groupId: String?) {
            persistence.transaction(afterCommit = { clearManualReceipts() }) { it.setActiveGroup(groupId) }
        }

        override suspend fun prune(groupIds: Set<String>) {
            persistence.transaction { rows ->
                if (rows.activeGroup() !in groupIds) rows.setActiveGroup(null)
            }
        }

        internal fun forgetManualReceipt(groupId: String) =
            authority.intentLinearizer.serialize {
                manual.remove(groupId)
                changes.value = Math.addExact(changes.value, 1L)
                Unit
            }

        internal fun clearManualReceipts() =
            authority.intentLinearizer.serialize {
                manual.clear()
                changes.value = Math.addExact(changes.value, 1L)
            }
    }

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class SelectorChoiceModule {
    @dagger.Binds abstract fun choices(store: SelectorActiveGroupStore): SelectorChoicePersistence
}
