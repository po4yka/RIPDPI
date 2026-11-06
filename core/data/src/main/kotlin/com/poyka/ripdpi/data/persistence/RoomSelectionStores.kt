package com.poyka.ripdpi.data.persistence

import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.ProfileSelectionStore
import com.poyka.ripdpi.data.selector.SelectorPersistence
import com.poyka.ripdpi.data.selector.SelectorRecords
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class RoomProfileSelectionStore
    @Inject
    constructor(
        private val database: ProfileDatabase,
    ) : ProfileSelectionStore {
        override suspend fun snapshot() = database.read { it.selection.snapshot() }

        override fun observe() =
            database
                .observe(ProfileSelectionRows.Namespace)
                .map { snapshot() }
                .distinctUntilChanged()
    }

@Singleton
internal class RoomXraySelectionStore
    @Inject
    constructor(
        private val database: ProfileDatabase,
    ) : XrayProviderSelectionStore {
        override suspend fun current() = database.read { it.selection.xray() }

        override suspend fun update(record: XrayProviderSelectionRecord) =
            database.write { it.selection.setXray(record) }
    }

@Singleton
internal class RoomSelectorPersistence
    @Inject
    constructor(
        private val database: ProfileDatabase,
        private val authority: PauseIntentAuthority,
    ) : SelectorPersistence {
        override suspend fun <T> transaction(
            afterCommit: (T) -> Unit,
            block: (SelectorRecords) -> T,
        ): T = database.withAuthority(authority, afterCommit) { block(it.selection) }

        override fun observeChoice(groupId: String) =
            database
                .observe(ProfileSelectionRows.ChoiceNamespace)
                .map { database.read { rows -> rows.selection.choice(groupId) } }
                .distinctUntilChanged()

        override fun observeActiveGroup() =
            database
                .observe(ProfileSelectionRows.Namespace)
                .map { database.read { rows -> rows.selection.activeGroup() } }
                .distinctUntilChanged()
    }
