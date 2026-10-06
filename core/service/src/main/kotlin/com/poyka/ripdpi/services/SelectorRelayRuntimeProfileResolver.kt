package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.mapRelayProfile
import com.poyka.ripdpi.data.selector.SelectorActiveGroupStore
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

internal data class SelectedSelectorRelay(
    val profile: RelayProfileRecord,
    val credentials: RelayCredentialRecord,
    val groupId: String,
    val memberId: String,
) {
    override fun toString() = "SelectedSelectorRelay([REDACTED])"
}

internal interface SelectorRelayRuntimeProfileResolver {
    suspend fun resolve(): SelectedSelectorRelay?
}

/** Reads only explicit selector ownership and maps its member without touching native stores. */
@Singleton
internal class DefaultSelectorRelayRuntimeProfileResolver
    @Inject
    constructor(
        private val activeGroupStore: SelectorActiveGroupStore,
        private val selectionStore: SelectorSelectionStore,
        private val groupRepository: ProxyGroupRepository,
    ) : SelectorRelayRuntimeProfileResolver {
        override suspend fun resolve(): SelectedSelectorRelay? {
            val groupId = activeGroupStore.activeGroupId.value ?: return null
            val group =
                groupRepository.list().singleOrNull { it.id == groupId }
                    ?: rejectSelection("Active selector group is missing or ambiguous")
            if (!group.isSelector) rejectSelection("Active group is not a selector")
            val memberId =
                selectionStore.snapshot(groupId).profileId
                    ?: rejectSelection("Active selector has no selected member")
            val member =
                group.members.singleOrNull { it.id == memberId }
                    ?: rejectSelection("Selected selector member is missing or ambiguous")
            if (member.groupId != groupId) rejectSelection("Selected member belongs to another group")
            val mapped =
                try {
                    mapRelayProfile(member)
                } catch (_: IllegalArgumentException) {
                    rejectSelection("Selected selector member has invalid relay configuration")
                } ?: rejectSelection("Selected selector member is not supported by the relay runtime")
            return SelectedSelectorRelay(mapped.profile, mapped.credentials, groupId, memberId)
        }
    }

private fun rejectSelection(message: String): Nothing =
    throw ServiceStartupRejectedException(FailureReason.RelayConfigRejected(message))

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SelectorRelayRuntimeProfileResolverModule {
    @Binds
    @Singleton
    abstract fun bindSelectorRelayRuntimeProfileResolver(
        resolver: DefaultSelectorRelayRuntimeProfileResolver,
    ): SelectorRelayRuntimeProfileResolver
}
