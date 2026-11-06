package com.poyka.ripdpi.data.fakes

import com.poyka.ripdpi.data.selector.SelectorPersistence
import com.poyka.ripdpi.data.selector.SelectorRecords
import com.poyka.ripdpi.data.selector.SelectorSelectionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Domain-test storage; disk durability and migration belong to the Room tests. */
class InMemorySelectorPersistence(
    activeGroupId: String? = null,
    choices: Map<String, SelectorSelectionSnapshot> = emptyMap(),
) : SelectorPersistence {
    private data class State(
        val activeGroup: String?,
        val choices: Map<String, SelectorSelectionSnapshot>,
        val revision: Long,
    )

    private val mutex = Mutex()
    private val state =
        MutableStateFlow(State(activeGroupId, choices, choices.values.maxOfOrNull { it.revision } ?: 0L))

    override suspend fun <T> transaction(
        afterCommit: (T) -> Unit,
        block: (SelectorRecords) -> T,
    ): T =
        mutex.withLock {
            val before = state.value
            var active = before.activeGroup
            var revision = before.revision
            val choices = before.choices.toMutableMap()
            val rows =
                object : SelectorRecords {
                    override fun choice(groupId: String): SelectorSelectionSnapshot =
                        choices[groupId] ?: SelectorSelectionSnapshot(null, false, revision)

                    override fun setChoice(
                        groupId: String,
                        profileId: String?,
                        manual: Boolean,
                    ) {
                        revision = Math.addExact(revision, 1L)
                        choices[groupId] = SelectorSelectionSnapshot(profileId, manual, revision)
                    }

                    override fun activeGroup(): String? = active

                    override fun setActiveGroup(groupId: String?) {
                        active = groupId
                    }

                    override fun clearSelections() {
                        revision = Math.addExact(revision, 1L)
                        choices.clear()
                        active = null
                    }
                }
            block(rows).also {
                state.value = State(active, choices.toMap(), revision)
                afterCommit(it)
            }
        }

    override fun observeChoice(groupId: String) =
        state.map { it.choices[groupId] ?: SelectorSelectionSnapshot(null, false, it.revision) }

    override fun observeActiveGroup() = state.map { it.activeGroup }
}
