package com.poyka.ripdpi.data.selector

import kotlinx.coroutines.flow.Flow

/** Access is confined to a non-suspending, authority-checked database transaction. */
interface SelectorRecords {
    fun choice(groupId: String): SelectorSelectionSnapshot

    fun setChoice(
        groupId: String,
        profileId: String?,
        manual: Boolean,
    )

    fun activeGroup(): String?

    fun setActiveGroup(groupId: String?)

    fun clearSelections()
}

interface SelectorPersistence {
    suspend fun <T> transaction(
        afterCommit: (T) -> Unit = {},
        block: (SelectorRecords) -> T,
    ): T

    fun observeChoice(groupId: String): Flow<SelectorSelectionSnapshot>

    fun observeActiveGroup(): Flow<String?>
}
