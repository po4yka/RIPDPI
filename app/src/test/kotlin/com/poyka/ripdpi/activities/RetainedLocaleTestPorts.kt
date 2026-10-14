package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.DurableCommandReceipt
import com.poyka.ripdpi.data.ProfileMutationPreparation
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeLocaleRelayProfileStore(
    initialProfiles: List<RelayProfileRecord>,
) : RelayProfileStore {
    var profiles = initialProfiles
        private set
    var listCalls = 0
        private set

    override suspend fun load(profileId: String): RelayProfileRecord? = profiles.firstOrNull { it.id == profileId }

    override suspend fun list(): List<RelayProfileRecord> {
        listCalls++
        return profiles
    }

    override suspend fun save(profile: RelayProfileRecord) {
        profiles = profiles.filterNot { it.id == profile.id } + profile
    }

    override suspend fun clear(profileId: String) {
        profiles = profiles.filterNot { it.id == profileId }
    }
}

internal class FakeLocaleProxyGroupRepository(
    initialGroups: List<ProxyGroup>,
) : ProxyGroupRepository {
    val state = MutableStateFlow(initialGroups)

    override suspend fun add(group: ProxyGroup) {
        state.value = state.value.filterNot { it.id == group.id } + group
    }

    override suspend fun update(group: ProxyGroup) {
        state.value = state.value.map { if (it.id == group.id) group else it }
    }

    override suspend fun delete(
        preparation: ProfileMutationPreparation,
        id: String,
    ) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun list(): List<ProxyGroup> = state.value

    override suspend fun replaceAll(
        receipt: DurableCommandReceipt,
        groups: List<ProxyGroup>,
    ) {
        state.value = groups
    }

    override suspend fun compensateReplacement(groups: List<ProxyGroup>) {
        state.value = groups
    }

    override fun groups(): Flow<List<ProxyGroup>> = state
}
